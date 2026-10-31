#!/usr/bin/env python3
import json,os,subprocess,time
from pathlib import Path
OUT=Path('/var/lib/pi-device-monitor/status.json')
def cpu():
    v=list(map(int,Path('/proc/stat').read_text().splitlines()[0].split()[1:9])); return sum(v),v[3]+v[4]
a=cpu(); time.sleep(0.2); b=cpu()
m={l.split(':')[0]:int(l.split()[1])*1024 for l in Path('/proc/meminfo').read_text().splitlines()}
data={'schema':1,'collected_at':int(time.time()),'cpu_percent':round(100*(1-(b[1]-a[1])/max(1,b[0]-a[0])),1),'memory_total':m['MemTotal'],'memory_available':m['MemAvailable'],'swap_total':m['SwapTotal'],'swap_free':m['SwapFree'],'uptime_seconds':int(float(Path('/proc/uptime').read_text().split()[0])),'disks':[],'containers':[],'alerts':[]}
try: data['temperature']=int(Path('/sys/class/thermal/thermal_zone0/temp').read_text())/1000
except OSError: data['temperature']=None
for name,path in [('microSD','/'),('Сервисы SSD','/srv/pi-services'),('Фото SSD','/srv/pi-photos')]:
    v=os.statvfs(path); free=v.f_bavail*v.f_frsize; total=v.f_blocks*v.f_frsize
    data['disks'].append({'name':name,'total':total,'free':free})
    if free<(20 if 'photos' in path else 5)*1024**3: data['alerts'].append('Мало места: '+name)
try:
    rows=subprocess.check_output(['docker','ps','-a','--format','{{json .}}'],timeout=15,text=True)
    for line in rows.splitlines():
        r=json.loads(line)
        # Stopped legacy containers are not reported as active-service failures.
        if r['State'] in ('running','restarting'):
            data['containers'].append({'name':r['Names'],'state':r['State'],'status':r['Status']})
            if r['State']=='restarting' or '(unhealthy)' in r['Status']: data['alerts'].append('Контейнер: '+r['Names'])
except Exception: data['alerts'].append('Не удалось прочитать Docker')
try:
    s=json.loads(Path('/var/lib/pi-server-watch/state.json').read_text())
    data['watch_age_seconds']=round(time.time()-Path('/var/lib/pi-server-watch/state.json').stat().st_mtime)
    data['watched']=[{'name':k,'failures':v.get('failures',0),'incident':v.get('incident',False),'attempts':len(v.get('attempts',[]))} for k,v in s.items() if not k.startswith('_')]
    data['alerts'] += ['Сервис: '+v['name'] for v in data['watched'] if v['incident']]
    if data['watch_age_seconds']>300: data['alerts'].append('Мониторинг восстановления не обновляется')
except Exception: data['alerts'].append('Нет данных мониторинга восстановления')
if data['temperature'] is not None and data['temperature']>=80: data['alerts'].append('Высокая температура')
data['load_average']=list(os.getloadavg())
data['cpu_cores']=os.cpu_count()
data['network_bytes']={}
for interface in ('eth0','wlan0','tailscale0'):
    try:
        root=Path('/sys/class/net')/interface/'statistics'
        data['network_bytes'][interface]={'rx':int((root/'rx_bytes').read_text()),'tx':int((root/'tx_bytes').read_text())}
    except OSError: pass
try:
    from probes import collect_services
    data['services']=collect_services()
    data['alerts'] += ['Недоступен: '+s['name'] for s in data['services'] if s['status']=='down']
except Exception:
    data['services']=[]
    data['alerts'].append('Проверки сервисов недоступны')
data['collected_at']=int(time.time())
OUT.parent.mkdir(exist_ok=True,parents=True)
tmp=OUT.with_suffix('.tmp'); tmp.write_text(json.dumps(data,ensure_ascii=False)); tmp.chmod(0o644); tmp.replace(OUT)
