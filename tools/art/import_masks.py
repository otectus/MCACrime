#!/usr/bin/env python3
"""Import static armor geometry and supplied PNGs from masks.zip (stdlib only).

Run from the repository root. Item IDs remain stable; archive names describe art.
The two missing icons use flat, tinted item geometry sampling the supplied face UVs.
No Python or source archive is needed to build or run the mod after importing.
"""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile

ASSETS = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/mcacrime'
# item id: (project, worn texture, icon, selected animal group)
MASKS = {
    'bandana': ('lower/bandana', 'lower/bandana_red', 'bandana', None),
    'highwaymans_domino': ('eyes/eyes', 'eyes/highwayman', None, None),
    'wrapped_scarf': ('scarf/scarf', 'scarf/scarf', 'scarf', None),
    'half_veil': ('lower/bandana', 'lower/veil', 'veil', None),
    'leather_mask': ('head/head', 'head/leatherface', 'leatherface', None),
    'raven_mask': ('animal/animal', 'animal/plague', 'plague', 'plague'),
    'jackal_mask': ('animal/animal', 'animal/jackal', 'jackal', 'jackal'),
    'stitched_mask': ('head/head', 'head/leather', 'stitched', None),
    'hockey_mask': ('face/face', 'face/hockey', 'jason', None),
    'clay_mask': ('face/face', 'face/clay_mask', 'clay_mask', None),
    'comedy_mask': ('comedy/comedy', 'comedy/comedy', 'comedy', None),
    'tragedy_mask': ('comedy/comedy', 'comedy/tragedy', 'tragedy', None),
    'iron_skull_mask': ('head/head', 'head/balaclava', 'balaclava', None),
    'brigand_visor': ('visor/visor', 'visor/visor', 'visor', None),
    'owl_mask': ('visor/visor', 'visor/owl', 'owl', None),
    'blank_iron_mask': ('face/face', 'face/iron', None, None),
}


def mirrored(vector):
    return [-vector[0], vector[1], vector[2]]


def rotation(vector):
    return [-vector[0], -vector[1], vector[2]]


def cube(element):
    """Blockbench -> Bedrock coordinates, with explicit UVs to retain fractional boxes."""
    assert element['type'] == 'cube'
    result = {
        'origin': [-element['to'][0], *element['from'][1:]],
        'size': [round(b - a, 5) for a, b in zip(element['from'], element['to'])],
        'uv': {},
    }
    if element.get('inflate'):
        result['inflate'] = element['inflate']
    if any(element.get('rotation', [])):
        result['pivot'] = mirrored(element['origin'])
        result['rotation'] = rotation(element['rotation'])
    for name, face in element['faces'].items():
        if face.get('texture') is None:
            continue
        assert not face.get('rotation'), 'Rotated UVs need an explicit conversion'
        u, v, end_u, end_v = face['uv']
        if name in ('up', 'down'):
            u, end_u, v, end_v = end_u, u, end_v, v
        result['uv'][name] = {'uv': [u, v], 'uv_size': [end_u - u, end_v - v]}
    return result


def geometry(project, model_id, selected):
    assert not project.get('animations'), 'Animation export is not supported'
    groups = {g['uuid']: g for g in project['groups']}
    elements = {e['uuid']: e for e in project['elements']}
    bones = []

    def visit(node, parent=None):
        group = groups[node['uuid']]
        name = group['name']
        if name in ('plague', 'jackal') and name != selected:
            return
        bone = {'name': name, 'pivot': mirrored(group['origin'])}
        if parent:
            bone['parent'] = parent
        if any(group.get('rotation', [])):
            bone['rotation'] = rotation(group['rotation'])
        cubes = []
        for child in node['children']:
            if isinstance(child, str):
                element = elements[child]
                # The archive accidentally disables the main lower/eye covering.
                # Both are required; otherwise these masks export only their knot.
                if element.get('export', True) or element['name'] in ('bandana', 'mask'):
                    cubes.append(cube(element))
        if cubes:
            bone['cubes'] = cubes
        bones.append(bone)
        for child in node['children']:
            if isinstance(child, dict):
                visit(child, name)

    for node in project['outliner']:
        visit(node)
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.' + model_id,
                        'texture_width': project['resolution']['width'],
                        'texture_height': project['resolution']['height'],
                        'visible_bounds_width': 3, 'visible_bounds_height': 3,
                        'visible_bounds_offset': [0, 1, 0]},
        'bones': bones,
    }]}


def item_model(model_id, icon):
    if icon:
        return {'parent': 'item/generated', 'textures': {'layer0': 'mcacrime:item/' + model_id}}
    # These projects have no matching icon in the archive. Sample the face directly
    # in a two-sided item plane, keeping resource-pack replacements and dye working.
    eye = model_id == 'highwaymans_domino'
    uv = [4, 4, 8, 5.5] if eye else [4, 12, 8, 16]
    return {
        'gui_light': 'front',
        'textures': {'mask': 'mcacrime:item/' + model_id,
                     'particle': '#mask'},
        'display': {
            'gui': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
            'ground': {'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
            'fixed': {'rotation': [0, 180, 0]},
            'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
            'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
            'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
        },
        'elements': [{'from': [2, 5.75 if eye else 2, 8], 'to': [14, 10.25 if eye else 14, 8],
                      'faces': {face: {'uv': uv, 'texture': '#mask', 'tintindex': 0}
                                for face in ('north', 'south')}}],
    }


def write_json(relative, value):
    path = ASSETS / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', nargs='?', default='masks.zip', type=Path)
    args = parser.parse_args()
    with ZipFile(args.archive) as archive:
        for model_id, (project_path, texture, icon, selected) in MASKS.items():
            project = json.loads(archive.read(project_path + '.bbmodel'))
            write_json('geo/masks/' + model_id + '.geo.json', geometry(project, model_id, selected))
            (ASSETS / ('textures/models/armor/' + model_id + '_layer_1.png')).write_bytes(archive.read(texture + '.png'))
            icon_path = ASSETS / ('textures/item/' + model_id + '.png')
            if icon:
                icon_path.write_bytes(archive.read('icons/' + icon + '.png'))
            else:
                icon_path.write_bytes(archive.read(texture + '.png'))
            write_json('models/item/' + model_id + '.json', item_model(model_id, icon))
    write_json('animations/masks.animation.json', {'format_version': '1.8.0', 'animations': {}})
    print('Imported all 16 masks')


if __name__ == '__main__':
    main()
