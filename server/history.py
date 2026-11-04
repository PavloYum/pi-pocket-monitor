"""Bounded seven-day metrics and incident history; no media or backups."""
import json,sqlite3,time,os,subprocess
from pathlib import Path
ROOT=Path('/var/lib/pi-device-monitor')
def enrich(d):
    ROOT.mkdir(parents=True,exist_ok=True)
    now=d['collected_at']; db=sqlite3.connect(ROOT/'history.db')
    db.execute('CREATE TABLE IF NOT EXISTS samples (ts INTEGER PRIMARY KEY, body TEXT NOT NULL)')
    db.execute('CREATE TABLE IF NOT EXISTS incidents (key TEXT PRIMARY KEY, opened INTEGER, last_seen INTEGER, closed INTEGER, detail TEXT)')
    previous=db.execute('SELECT body FROM samples ORDER BY ts DESC LIMIT 1').fetchone()
    prev=json.loads(previous[0]) if previous else None
    d['network_rate']={};d['disk_io']={}
    raw={}
    for line in Path('/proc/diskstats').read_text().splitlines():
        v=line.split()
        if v[2] in ('sda','mmcblk0'):raw[v[2]]={'read':int(v[5])*512,'write':int(v[9])*512}
    d['disk_bytes']=raw
    if prev and d['uptime_seconds']>prev['uptime_seconds'] and 0<now-prev['collected_at']<300:
        dt=now-prev['collected_at']
        for field,out in [('network_bytes','network_rate'),('disk_bytes','disk_io')]:
            for name,values in d.get(field,{}).items():
                old=prev.get(field,{}).get(name,{})
                if all(k in old and values[k]>=old[k] for k in values):d[out][name]={k:round((v-old[k])/dt) for k,v in values.items()}
    try:
        flags=int(subprocess.check_output(['vcgencmd','get_throttled'],text=True,timeout=3).strip().split('=')[1],16)
        d['power']={'undervoltage_now':bool(flags&1),'throttled_now':bool(flags&4),'undervoltage_since_boot':bool(flags&65536),'throttled_since_boot':bool(flags&262144)}
        if flags&5:d['alerts'].append('Питание/троттлинг: активное предупреждение')
    except Exception:d['power']=None
    cache=ROOT/'hardware.json'
    try:
        hardware=json.loads(cache.read_text())
    except (OSError,ValueError):hardware={}
    if now-hardware.get('checked_at',0)>300:
        hardware={'checked_at':now,'smart':None,'kernel_errors':None}
        try:
            proc=subprocess.run(['smartctl','-H','-j','/dev/sda'],capture_output=True,text=True,timeout=8)
            hardware['smart']=json.loads(proc.stdout).get('smart_status',{}).get('passed')
        except Exception:pass
        try:
            import re
            lines=subprocess.check_output(['journalctl','-k','--since','-5min','--no-pager','-o','cat'],text=True,timeout=5)
            hardware['kernel_errors']=len(re.findall(r'I/O error|EXT4-fs error|reset .*USB device|Buffer I/O|nvme.*timeout',lines,re.I))
        except Exception:pass
        cache.write_text(json.dumps(hardware));cache.chmod(0o600)
    d['hardware']=hardware
    if hardware['smart'] is False:d['alerts'].append('SSD SMART: ошибка здоровья')
    if hardware['kernel_errors']:d['alerts'].append('Ошибки диска/USB за последние 5 минут')
    for path in ('/srv/pi-services' ,'/srv/pi-photos'):
        if not os.path.ismount(path):d['alerts'].append('Отсутствует монтирование '+path)
    try: until=int((ROOT/'maintenance-until').read_text())
    except (OSError,ValueError):until=0
    d['maintenance_until']=until;d['maintenance']=until>now
    keys=set(d['alerts'])
    if not d['maintenance']:
        for key in keys:
            db.execute('INSERT INTO incidents VALUES(?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET opened=CASE WHEN closed IS NOT NULL THEN excluded.opened ELSE opened END,last_seen=excluded.last_seen,closed=NULL,detail=excluded.detail',(key,now,now,None,key))
        for (key,) in db.execute('SELECT key FROM incidents WHERE closed IS NULL').fetchall():
            if key not in keys:db.execute('UPDATE incidents SET closed=? WHERE key=?',(now,key))
    d['incidents']=[dict(zip(('key','opened','last_seen','closed','detail'),r)) for r in db.execute('SELECT * FROM incidents ORDER BY last_seen DESC LIMIT 30')]
    db.execute('INSERT OR REPLACE INTO samples VALUES(?,?)',(now,json.dumps({k:d[k] for k in ('collected_at','uptime_seconds','cpu_percent','temperature','memory_available','memory_total','disks','network_bytes','network_rate','disk_bytes','disk_io')},ensure_ascii=False)))
    db.execute('DELETE FROM samples WHERE ts<?',(now-7*86400,));db.execute('DELETE FROM incidents WHERE closed IS NOT NULL AND closed<?',(now-7*86400,));db.commit()
    # Compact timestamped graph points, max 120 per period. Gaps remain visible.
    d['history']={}
    for name,seconds in [('hour',3600),('day',86400),('week',604800)]:
        rows=db.execute('SELECT ts,body FROM samples WHERE ts>=? ORDER BY ts',(now-seconds,)).fetchall();step=max(1,(len(rows)+119)//120)
        points=[]
        for ts,body in rows[::step]:
            x=json.loads(body);points.append({'ts':ts,'cpu':x['cpu_percent'],'temperature':x.get('temperature'),'ram':round(100*(1-x['memory_available']/x['memory_total']),1),'free_gb':round(min(v['free'] for v in x['disks'])/1073741824,1),'rx_mbps':round(sum(v.get('rx',0) for k,v in x.get('network_rate',{}).items() if k in ('eth0','wlan0'))/1e6,2),'disk_mbps':round(sum(v.get('write',0) for v in x.get('disk_io',{}).values())/1e6,2)})
        d['history'][name]=points
    db.close();(ROOT/'history.db').chmod(0o600)
