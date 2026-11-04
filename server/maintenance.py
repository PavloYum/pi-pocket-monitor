#!/usr/bin/env python3
"""Run on Pi as administrator: maintenance.py 30 (minutes), or 0 to end."""
import sys,time
from pathlib import Path
minutes=int(sys.argv[1])
if not 0<=minutes<=1440:raise SystemExit('Minutes must be 0..1440')
p=Path('/var/lib/pi-device-monitor/maintenance-until');p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(str(int(time.time())+60*minutes));p.chmod(0o644)
print('Maintenance minutes:',minutes)
