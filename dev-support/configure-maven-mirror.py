#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
from pathlib import Path
import xml.etree.ElementTree as ET

path = Path.home() / '.m2/settings.xml'
tree = ET.parse(path)
root = tree.getroot()
namespace = root.tag.removesuffix('settings')
ET.register_namespace('', namespace[1:-1])
mirrors = ET.SubElement(root, namespace + 'mirrors')
mirror = ET.SubElement(mirrors, namespace + 'mirror')
for name, value in {'id': 'google-central', 'url': os.environ['MVNW_REPOURL'], 'mirrorOf': 'central'}.items():
    ET.SubElement(mirror, namespace + name).text = value
tree.write(path, encoding='UTF-8', xml_declaration=True)
