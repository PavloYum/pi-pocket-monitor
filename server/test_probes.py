import unittest
from probes import assess
class ProbeTests(unittest.TestCase):
 def test_missing(self): self.assertEqual(assess({'name':'a'}, {})['status'],'down')
 def test_network_lost(self):
  c={'State':{'Status':'running'},'NetworkSettings':{'Networks':{}},'HostConfig':{'NetworkMode':'test'}}
  self.assertEqual(assess({'name':'a'},{'a':c})['status'],'down')
 def test_process_is_not_health(self):
  c={'State':{'Status':'running'},'NetworkSettings':{'Networks':{}},'HostConfig':{'NetworkMode':'host'}}
  self.assertEqual(assess({'name':'a'},{'a':c})['status'],'limited')
 def test_stopped(self):self.assertEqual(assess({'name':'a'},{'a':{'State':{'Status':'exited'}}})['status'],'down')
if __name__=='__main__':unittest.main()
