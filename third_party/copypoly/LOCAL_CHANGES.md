# Local CopyPoly changes

All 39 imported production Java files retain MIT/copyright headers.
37 retain the original implementation. `Copypoly.java` changes the block, item
and block entity ID to `create_fire_control:camouflaged_polyhedral_armor` and adds
legacy aliases; `PolyClient.java` follows that model ID. Translations now name
the block/menu Camouflaged Polyhedral Armor (伪装多面体装甲). Recipe outputs,
loot and block tags follow the new ID. `UPSTREAM_FILES.json` records both the
supplied source SHA-256 and the actual local SHA-256, including these changes.

`fire_control_adapter/java/.../PolyArmorModel.java` adapts the original material
projection renderer for exact damage meshes. It is explicitly MIT, credits
caca and BaiCaiYu1123, and is packaged in the separate CopyPoly sources archive.
`fire_control_adapter/java/com/caca/copypoly/client/ArmorEditorView.java` is an
explicitly MIT facade over the original free camera, Sable-aware projection and
overlay. It also aims the camera at the edited block when opening/resetting.
Its point markers and axis labels reuse the original editor's pixel-scaled
normal/custom/selected conventions. `fire_control_adapter/resources` contains
the MIT blockstate, item model and loot JSON required by the migrated ID.

The armor CSG geometry, editable perforated mesh, own mesh editor/menu/payloads,
bore persistence, CBC armor provider, projectile impact scope and
CBC/Terminal Ballistics/Sable mixins were implemented separately in
the host's own source tree. They are original Fire Control work and are not
licensed under this directory's MIT license. Their use of the MIT renderer
does not apply MIT to the rest of Fire Control. See the host LICENSE and
THIRD_PARTY_NOTICES.md for the complete license boundaries.

The host's client-only appearance mixins apply the Fire Control computer's
brass frame/buttons and cream panel to the upstream world/table editor. They
reuse the host's own painter without changing the imported editor source,
actions, mesh compilation or MIT attribution.

Terminal Ballistics was inspected as a locally supplied dependency; its code
is not copied into this component or bundled in the Fire Control JAR.

## Both editor routes (2026-11-17)

The undamaged-block PolyScreen now also has persistent vertex/edge/face selection modes, mode-specific replacement marquee selection, the shared host x-ray setting and compact controls. Its original vertex creation, line cutting, face construction, world camera and 5-second missing-face diagnostics remain available. World and preview overlays share the MIT depth renderer; preview mode rasterizes its own mesh depth and does not use the surrounding world as an occluder. The host binds its own client config through the MIT EditorPreferences bridge.

## Group editing (2026-11-18)

The host provides an optional connected block menu using the MIT PolyScreen tools. CopyPoly
now keeps one thousandth of a pixel in coordinates, with version 4 graph data when needed.
Legacy integer and tenth pixel data still decode. Normal and Ctrl axis steps remain one
pixel and one tenth of a pixel. The host owns joining solids, clipping them to the original
cells and validating their world permissions and revisions.

The shared editor can remove redundant controls from a draft while preserving its exact
surface and authored guides. Group menus can also refresh after the host clears the
recorded holes in the participating blocks.
The host projects each copied material onto the group draft surface in world and preview modes.
The group editor can switch between copied textures and the translucent draft, with the choice saved by the host. Captured camera gestures use relative mouse motion without panel hover.
