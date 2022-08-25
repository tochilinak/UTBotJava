import sys
sys.path.append('/home/vyacheslav/Desktop/utbot/UTBotJava/utbot-python/samples/samples')
import unittest
import builtins
import quick_sort


class TestTopLevelFunctions(unittest.TestCase):
    # region Test suites for executable quick_sort.quick_sort
    # region
    def test_quick_sort(self):
        actual = quick_sort.quick_sort([4294967296])
        
        self.assertEqual([4294967296], actual)
    
    def test_quick_sort1(self):
        actual = quick_sort.quick_sort([-1, 4294967297, 123, 4294967296, 4294967296, -3])
        
        self.assertEqual([-3, -1, 123, 4294967296, 4294967296, 4294967297], actual)
    
    def test_quick_sort2(self):
        actual = quick_sort.quick_sort([])
        
        self.assertEqual([], actual)
    
    # endregion
    
    # endregion
    

