#!/usr/bin/env python
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

##
## Generate a yml from env.py
##
## ./gen-yml-from-env.py <template yml file> [<template yml file>]
##

import os, sys
import yaml

if len(sys.argv) < 2:
    print 'Usage: %s' % (sys.argv[0])
    sys.exit(1)

conf_files = sys.argv[1:]

for conf_filename in conf_files:
    conf = yaml.load(open(conf_filename))

    # update the config
    modified = False
    for k in sorted(os.environ.keys()):
        key_parts = k.split('_')
        v = os.environ[k]

        i = 0
        conf_to_modify = conf
        while i < len(key_parts):
            key_part = key_parts[i]
            if not key_part in conf_to_modify:
                break

            if i == (len(key_parts) - 1):
                if key_part == 'workerPort':
                    conf_to_modify[key_part] = int(v)
                else:
                    conf_to_modify[key_part] = v

                modified = True
            else:
                conf_to_modify = conf_to_modify[key_part]
            i += 1
    # Store back the updated config in the same file
    f = open(conf_filename , 'w')
    yaml.dump(conf, f, default_flow_style=False)
    f.close()
