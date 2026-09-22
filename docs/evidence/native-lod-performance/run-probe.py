#!/usr/bin/env python3
"""Compile the pre-change mesher beside the current one; run a short paired CPU probe."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
baseline = '34bf32f8ca6acba684cbca98a35a8171243e9f08'
package = 'src/client/java/dev/metalcraft/client/chunk/'
java = Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home', '-v', '25'], text=True).strip()) / 'bin'
with tempfile.TemporaryDirectory(prefix='metalcraft-native-lod-probe-') as directory:
    temporary = Path(directory)
    old = subprocess.check_output(['git', 'show', baseline + ':' + package + 'NativeGeometryMesher.java'], cwd=root, text=True)
    reference = temporary / 'NativeGeometryMesherBaseline.java'
    reference.write_text(old.replace('NativeGeometryMesher', 'NativeGeometryMesherBaseline').replace('NativeSurfaceMesher', 'NativeSurfaceMesherBaseline'))
    old_surface = subprocess.check_output(['git', 'show', baseline + ':' + package + 'NativeSurfaceMesher.java'], cwd=root, text=True)
    surface_reference = temporary / 'NativeSurfaceMesherBaseline.java'
    surface_reference.write_text(old_surface.replace('NativeSurfaceMesher', 'NativeSurfaceMesherBaseline'))
    sources = [root / package / name for name in ['NativeSurfaceMesher.java', 'NativeGeometryMesher.java', 'NativeLodSelection.java']]
    sources += [reference, surface_reference, root / 'src/smoke/java/dev/metalcraft/client/chunk/NativeGeometryLodSmoke.java', Path(__file__).with_name('Probe.java')]
    subprocess.run([str(java / 'javac'), '-d', directory, *map(str, sources)], check=True)
    subprocess.run([str(java / 'java'), '-Xms256m', '-Xmx256m', '-cp', directory, 'dev.metalcraft.client.chunk.Probe'], check=True)
