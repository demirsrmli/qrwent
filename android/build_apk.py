#!/usr/bin/env python3
"""Direct SDK build without Gradle downloads; requires JRE17, SDK35 and ECJ."""
import argparse,pathlib,subprocess,os,zipfile,shutil,secrets,xml.etree.ElementTree as ET
ap=argparse.ArgumentParser();ap.add_argument('--sdk',required=True);ap.add_argument('--ecj',required=True);ap.add_argument('--zxing',required=True);ap.add_argument('--out',required=True);ap.add_argument('--keystore');args=ap.parse_args()
root=pathlib.Path(__file__).resolve().parent;sdk=pathlib.Path(args.sdk).resolve();bt=next((p for p in [sdk/'build-tools/35.0.0',sdk/'android-15'] if (p/'aapt2').exists()),None);platform=next((p for p in [sdk/'platforms/android-35/android.jar',sdk/'android-35/android.jar'] if p.exists()),None)
if not bt or not platform:raise SystemExit('SDK platform 35 and build-tools 35.0.0 required')
build=root/'build-local';shutil.rmtree(build,ignore_errors=True);build.mkdir();(build/'classes').mkdir();(build/'dex').mkdir();(build/'generated').mkdir()
def run(*cmd):subprocess.run([str(x) for x in cmd],check=True,env=signing_env if 'signing_env' in globals() else None)
res=root/'app/src/main/res';manifest=root/'app/src/main/AndroidManifest.xml';assets=root/'app/src/main/assets'
# AGP uses namespace in build.gradle; direct aapt2 requires package on its temporary manifest.
ET.register_namespace('android','http://schemas.android.com/apk/res/android');tree=ET.parse(manifest);tree.getroot().set('package','org.qrwent.app');manifest=build/'AndroidManifest.xml';tree.write(manifest,encoding='utf-8',xml_declaration=True)
run(bt/'aapt2','compile','--dir',res,'-o',build/'resources.zip')
run(bt/'aapt2','link','-o',build/'resources.apk','-I',platform,'--manifest',manifest,'-A',assets,'--java',build/'generated','--min-sdk-version','29','--target-sdk-version','35','--version-code','1','--version-name','1.0.0',build/'resources.zip')
sources=sorted((root/'app/src/main/java').rglob('*.java'))+sorted((build/'generated').rglob('*.java'))
run('java','-Xmx768m','-jar',pathlib.Path(args.ecj).resolve(),'-8','-encoding','UTF-8','-warn:none','-classpath',os.pathsep.join([str(platform),str(pathlib.Path(args.zxing).resolve())]),'-d',build/'classes',*sources)
classes=build/'classes.jar'
with zipfile.ZipFile(classes,'w',zipfile.ZIP_DEFLATED) as z:
 for p in (build/'classes').rglob('*.class'):z.write(p,p.relative_to(build/'classes'))
run('java','-Xmx768m','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','29','--lib',platform,'--output',build/'dex',classes,pathlib.Path(args.zxing).resolve())
unsigned=build/'unsigned.apk';shutil.copyfile(build/'resources.apk',unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 for p in (build/'dex').glob('*.dex'):z.write(p,p.name)
run(bt/'zipalign','-f','4',unsigned,build/'aligned.apk')
ks=pathlib.Path(args.keystore).resolve() if args.keystore else build/'local-signing.jks'
password=os.environ.get('QRWENT_KEYSTORE_PASSWORD') or secrets.token_urlsafe(24)
signing_env=dict(os.environ);signing_env['QRWENT_SIGN_PASS']=password
if not args.keystore:run('keytool','-genkeypair','-keystore',ks,'-storepass:env','QRWENT_SIGN_PASS','-keypass:env','QRWENT_SIGN_PASS','-alias','qrwent','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=qrwent Local Build, O=qrwent')
if args.keystore and not os.environ.get('QRWENT_KEYSTORE_PASSWORD'):raise SystemExit('Set QRWENT_KEYSTORE_PASSWORD for an existing keystore')
output=pathlib.Path(args.out).resolve();output.parent.mkdir(parents=True,exist_ok=True)
signing_env=dict(os.environ);signing_env['QRWENT_SIGN_PASS']=password
subprocess.run(['java','-jar',str(bt/'lib/apksigner.jar'),'sign','--ks',str(ks),'--ks-key-alias','qrwent','--ks-pass','env:QRWENT_SIGN_PASS','--key-pass','env:QRWENT_SIGN_PASS','--out',str(output),str(build/'aligned.apk')],env=signing_env,check=True)
run('java','-jar',bt/'lib/apksigner.jar','verify','--verbose',output)
if not args.keystore:ks.unlink(missing_ok=True)
print('Built',output,output.stat().st_size,'bytes')
