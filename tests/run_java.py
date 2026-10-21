#!/usr/bin/env python3
"""Run host protocol tests using the actual Android protocol source classes."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--zxing', required=True, type=Path)
parser.add_argument('--json', required=True, type=Path)
parser.add_argument('--ecj', type=Path, help='Optional ECJ JAR instead of javac')
parser.add_argument('--browser', action='store_true', help='Also verify qa browser frame/image outputs')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = root / 'qa' / 'java-classes'
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)
classpath = os.pathsep.join(str(p.resolve()) for p in (args.zxing, args.json))
sources = [root / 'android/app/src/main/java/org/qrwent/app' / (name + '.java')
           for name in ('QrProtocol', 'ReceiveSession', 'SenderSequence')]
sources.append(root / 'tests/ProtocolTest.java')
if args.ecj:
    compiler = ['java', '-jar', str(args.ecj.resolve()), '-8', '-warn:none']
else:
    compiler = ['javac', '--release', '8']
subprocess.run(compiler + ['-encoding', 'UTF-8', '-cp', classpath, '-d', str(out)]
               + [str(p) for p in sources], check=True)
command = ['java', '-Xmx32m', '-Djava.awt.headless=true', '-cp', str(out) + os.pathsep + classpath,
           'org.qrwent.app.ProtocolTest', str(root / 'tests/vectors.json')]
if args.browser:
    command += [str(root / 'qa/browser-qr.png'), str(root / 'qa/browser-transfer.json')]
subprocess.run(command, check=True)
