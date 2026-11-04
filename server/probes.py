"""Read-only service probes; never execute configuration-provided commands."""
import json,socket,subprocess,time,urllib.request,urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

def assess(spec, containers):
    start=time.monotonic()
    result={'name':spec.get('label',spec['name']),'container':spec['name'],'checked_at':int(time.time()),'status':'unknown','method':'container','detail':'Проверка приложения не настроена'}
    def finish(status,detail):
        result.update(status=status,detail=detail,latency_ms=round((time.monotonic()-start)*1000));return result
    c=containers.get(spec['name'])
    expected=set(spec.get('networks',[]))
    actual=c.get('NetworkSettings',{}).get('Networks',{}) if c else {}
    running=c is not None and c['State']['Status']=='running'
    result['networks']=[{'name':name,'required':name in expected,'connected':running and name in actual and bool(actual[name].get('EndpointID'))} for name in sorted(expected|set(actual))]
    result['network_mode']=c.get('HostConfig',{}).get('NetworkMode','unknown') if c else 'unknown'
    if c is None:return finish('down','Контейнер отсутствует')
    if c['State']['Status']!='running':return finish('down','Контейнер '+c['State']['Status'])
    networks=c['NetworkSettings']['Networks']; ips=[n['IPAddress'] for n in networks.values() if n.get('IPAddress')]
    if not ips and c['HostConfig']['NetworkMode'] not in ('host','none'):return finish('down','Нет подключения к Docker-сети')
    missing={n['name'] for n in result['networks'] if n['required'] and not n['connected']}
    if missing:return finish('down','Нет Docker-сетей: '+', '.join(sorted(missing)))
    kind=spec.get('type','health');result['method']=kind
    try:
        if kind=='http':
            url=spec.get('url') or 'http://'+ips[0]+':'+str(spec['port'])+spec.get('path','/')
            try:
                with urllib.request.urlopen(url,timeout=3) as r: code=r.status
            except urllib.error.HTTPError as e:code=e.code
            return finish('up' if code in spec.get('codes',[200]) else 'down','HTTP '+str(code))
        if kind=='postgres':
            output=subprocess.check_output(['docker','exec',spec['name'],'sh','-c','psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT 1"'],timeout=5,stderr=subprocess.DEVNULL,text=True)
            return finish('up' if output.strip()=='1' else 'down','SQL SELECT 1')
        if kind=='camera':
            output=subprocess.check_output(['docker','exec',spec['name'],'ffprobe','-v','error','-rtsp_transport','tcp','-show_entries','stream=codec_type','-of','json','rtsp://127.0.0.1:8554/camera'],timeout=12,stderr=subprocess.DEVNULL,text=True)
            tracks=[x['codec_type'] for x in json.loads(output).get('streams',[])]
            return finish('up' if 'video' in tracks and 'audio' in tracks else 'down','Дорожки: '+', '.join(tracks))
        if kind=='mqtt':
            # CONNECT with a random diagnostic ID, no credentials or application messages.
            import uuid
            cid=('monitor-'+uuid.uuid4().hex[:8]).encode();body=b'\x00\x04MQTT\x04\x02\x00\x05'+len(cid).to_bytes(2,'big')+cid
            with socket.create_connection((ips[0],spec['port']),timeout=3) as sock:
                sock.sendall(bytes([16,len(body)])+body);reply=sock.recv(4)
                if len(reply)==4 and reply[:2]==b'\x20\x02':
                    if reply[3]==0:sock.sendall(b'\xe0\x00');return finish('limited','MQTT CONNECT принят; обмен данными не проверен')
                    return finish('limited','MQTT отвечает; для тестовой публикации нужны отдельные credentials')
                return finish('down','Некорректный MQTT CONNACK')
        if kind=='tcp':
            with socket.create_connection((ips[0],spec['port']),timeout=3):pass
            return finish('limited','TCP-порт доступен; функции приложения не проверены')
        health=c['State'].get('Health',{}).get('Status')
        if health=='healthy':return finish('up','Docker healthcheck проходит')
        if health=='unhealthy':return finish('down','Docker healthcheck не проходит')
        if health=='starting':return finish('unknown','Запускается')
        return finish('limited','Процесс работает; функции приложения не проверены')
    except Exception:return finish('down','Нет ответа на проверку')

def collect_services():
    specs=json.loads(Path(__file__).with_name('services.json').read_text())
    names=[s['name'] for s in specs]
    # Inspect existing containers together; missing names are retained as down.
    ids=subprocess.check_output(['docker','ps','-aq'],text=True,timeout=10).split()
    items=json.loads(subprocess.check_output(['docker','inspect',*ids],text=True,timeout=15)) if ids else []
    containers={c['Name'].lstrip('/'):c for c in items}
    with ThreadPoolExecutor(max_workers=8) as pool:
        return list(pool.map(lambda s:assess(s,containers),specs))
