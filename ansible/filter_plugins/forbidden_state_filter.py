#!/usr/bin/python
# -*- coding: utf-8 -*-

# Filters are typically defined as a class that returns a dictionary of filters.
# This filter receives the permutation state and the forbidden combinations.
class FilterModule(object):
    def filters(self):
        return {
            'remove_forbidden': self.remove_forbidden
        }

    def remove_forbidden(self, current, forbidden):
        accepted = []
        for curr in current:
            is_added = False
            for f in forbidden:
                for k, v in f.items():
                    if k in curr and self.is_allowed(v, curr.get(k)):
                        accepted.append(curr)
                        is_added = True
                        break
            
                if is_added:
                    break
        
        return accepted
    
    def is_allowed(self, a, b):
        if isinstance(a, str):
            return a is not b
        
        if isinstance(a, list):
            return not set(a).issubset(set(b))
        
        return True
