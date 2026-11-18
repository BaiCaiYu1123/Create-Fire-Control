"""Extract the two official load curves by following the event graph, retaining source IDs."""
import hashlib, json, pathlib, xml.etree.ElementTree as ET

source = pathlib.Path('reference/ztz99a/fmod_event.xml')
objects = {o.get('id'): o for o in ET.parse(source).getroot()}
def prop(o, name, default=None):
    p = o.find(f"property[@name='{name}']/value")
    return p.text if p is not None else default
def rel(o, name):
    return [v.text for v in o.findall(f"relationship[@name='{name}']/destination")]
parameter = '{5ef70fb5-a09f-4cf2-bf00-99ac247b0865}'
curves = {}
for group in objects.values():
    name = prop(group, 'name')
    if group.get('class') != 'EventMixerGroup' or name not in ('layer00', 'layer06'): continue
    for automator_id in rel(group, 'automators'):
        automator = objects[automator_id]
        assert prop(automator, 'nameOfPropertyBeingAutomated') == 'volume'
        for curve_id in rel(automator, 'automationCurves'):
            curve = objects[curve_id]
            if rel(curve, 'parameter') != [parameter]: continue
            points = []
            for point_id in rel(curve, 'automationPoints'):
                point = objects[point_id]
                points.append(dict(id=point_id, load=float(prop(point, 'position')),
                                   db=float(prop(point, 'value')), curveShape=float(prop(point, 'curveShape', '0'))))
            curves[name] = dict(groupId=group.get('id'), curveId=curve_id, points=sorted(points, key=lambda p: p['load']))
assert set(curves) == {'layer00', 'layer06'}
result = dict(source='https://github.com/GaijinEntertainment/fmod_studio_warthunder_for_modders/blob/master/Metadata/Event/%7B63385eb2-f16b-4574-9740-3f308964a659%7D.xml',
              sha256=hashlib.sha256(source.read_bytes()).hexdigest(), parameterId=parameter,
              interpolation='Default shapes: 1001 native FMOD response points normalized to these endpoints, interpolated in dB. Custom shapes smoothly blend/warp these tables (approximate). See native_load_response.json for provenance.',
              curves=curves)
dest = pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/ztz99a/gain_reference.json')
dest.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
print(json.dumps(curves, indent=2))
