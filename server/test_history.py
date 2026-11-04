import unittest,tempfile,time,json
from pathlib import Path
from unittest.mock import patch
import history
class HistoryTests(unittest.TestCase):
 def test_retention_and_rates(self):
  with tempfile.TemporaryDirectory() as path,patch.object(history,'ROOT',Path(path)),patch.object(history.subprocess,'check_output',side_effect=OSError),patch.object(history.subprocess,'run',side_effect=OSError),patch.object(history.os.path,'ismount',return_value=True):
   now=int(time.time())
   def sample(ts,rx):return dict(collected_at=ts,uptime_seconds=ts,cpu_percent=10,temperature=30,memory_available=50,memory_total=100,disks=[{'free':100}],network_bytes={'eth0':{'rx':rx,'tx':rx}},alerts=['test'])
   a=sample(now,100);history.enrich(a)
   b=sample(now+60,700);history.enrich(b)
   self.assertEqual(b['network_rate']['eth0']['rx'],10)
   self.assertEqual(len(b['history']['hour']),2)
   self.assertEqual(len(b['incidents']),1)
   c=sample(now+120,800);c['alerts']=[];history.enrich(c)
   self.assertIsNotNone(c['incidents'][0]['closed'])
 def test_maintenance(self):
  with tempfile.TemporaryDirectory() as path,patch.object(history,'ROOT',Path(path)),patch.object(history.subprocess,'check_output',side_effect=OSError),patch.object(history.subprocess,'run',side_effect=OSError),patch.object(history.os.path,'ismount',return_value=True):
   (Path(path)/'maintenance-until').write_text(str(int(time.time())+600))
   d=dict(collected_at=int(time.time()),uptime_seconds=1,cpu_percent=0,temperature=None,memory_available=1,memory_total=2,disks=[{'free':1}],network_bytes={},alerts=['test'])
   history.enrich(d);self.assertTrue(d['maintenance']);self.assertEqual(d['incidents'],[])
if __name__=='__main__':unittest.main()
