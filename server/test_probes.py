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

class NetworkDetailsTests(unittest.TestCase):
 def test_connected_and_missing_required(self):
  c={'State':{'Status':'running'},'NetworkSettings':{'Networks':{'a':{'IPAddress':'172.18.0.2','EndpointID':'abc'}}},'HostConfig':{'NetworkMode':'a'}}
  r=assess({'name':'x','networks':['a','b']},{'x':c})
  self.assertEqual(r['status'],'down')
  self.assertEqual(r['networks'],[{'name':'a','required':True,'connected':True},{'name':'b','required':True,'connected':False}])
 def test_missing_container_networks(self):
  self.assertFalse(assess({'name':'x','networks':['a']},{})['networks'][0]['connected'])
 def test_stopped_not_green(self):
  c={'State':{'Status':'exited'},'NetworkSettings':{'Networks':{'a':{'EndpointID':'abc'}}},'HostConfig':{'NetworkMode':'a'}}
  self.assertFalse(assess({'name':'x'},{'x':c})['networks'][0]['connected'])
