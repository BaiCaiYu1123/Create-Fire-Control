# Third-party components

This is a **mixed-license** project. The root [LICENSE](LICENSE) reserves rights
only in original Fire Control work. Third-party code and assets retain their
licenses even inside the Fire Control package or resource namespace. The list
below does not override any additional third-party copyright or license notice.

## CopyPoly 1.9.1

- Author and copyright: Copyright (c) 2026 caca.
- License: MIT; full text: [third_party/copypoly/LICENSE](third_party/copypoly/LICENSE).
- Source supplied for this integration: a user-provided CopyPoly 1.9.1 source
  snapshot. No upstream repository URL or Git revision was supplied with it.
- Separate source tree: [third_party/copypoly](third_party/copypoly); package:
  `com.caca.copypoly`.
- JAR contents covered by MIT: `com/caca/copypoly/**`, `assets/copypoly/**`,
  `data/copypoly/**`, `copypoly.mixins.json`,
  `data/create/tags/block/copycat_deny.json`, and the CopyPoly entry in
  `data/minecraft/tags/block/mineable/pickaxe.json`.
- The MIT adapter also supplies
  `assets/create_fire_control/blockstates/camouflaged_polyhedral_armor.json`,
  `assets/create_fire_control/models/item/camouflaged_polyhedral_armor.json` and
  `data/create_fire_control/loot_table/blocks/camouflaged_polyhedral_armor.json`.
- The block, item and block entity use
  `create_fire_control:camouflaged_polyhedral_armor`; registry aliases retain
  old `copypoly:copycat_polyhedron` saved data. Packets and the original menu
  retain their `copypoly` identity. The same JAR supplies both mod IDs;
  installing a second standalone CopyPoly JAR would duplicate the mod ID.
- JAR license and attribution: `META-INF/licenses/copypoly-MIT.txt` and
  `META-INF/notices/copypoly.txt`.
- Original files and hashes: `third_party/copypoly/UPSTREAM_FILES.json`.
- Local changes: MIT/copyright headers on imported Java files; the Chinese and
  English display names now describe camouflaged polyhedral armor.
  `Copypoly.java` adapts registration/aliases and `PolyClient.java` follows the
  new model ID; both remain MIT. The single-block editor's 256-point limit and
  incremental convex hull also live in the MIT component.
  CopyPoly's bundled recipe uses the Fire Control X-shaped armor pattern and
  remains part of the MIT component.
  Host build/metadata, the shared pickaxe tag, and the Fire Control creative tab
  supply the integration. Retained upstream build files are reference files;
  the host uses `gradle/copypoly.gradle`.
- `third_party/copypoly/fire_control_adapter/java/com/hooya/stabilizedturret/client/armor/PolyArmorModel.java`
  adapts the original MIT material-projection renderer for runtime damage
  meshes. That file and the corresponding
  `com/hooya/stabilizedturret/client/armor/PolyArmorModel*.class` JAR entries
  are also MIT, with the original author and adaptation copyright retained.
  `fire_control_adapter/java/com/caca/copypoly/client/ArmorEditorView.java` is
  an explicitly MIT facade for the original camera, Sable projection and
  overlay. The original Fire Control armor geometry, ballistics, persistence
  and compatibility implementation, including the perforated-mesh editor, menu
  and payloads, in the host source tree and host mixins use the Fire Control
  original-work license; using the MIT renderer does not relicense them.
- The retained upstream Gradle launcher scripts/wrapper are build tooling;
  their existing Apache-2.0 notices remain applicable and are not replaced by
  MIT.

## Drive-By-Wire code and assets (MIT)

Copyright (c) 2023 endalion. Code copied or adapted from Drive-By-Wire retains
MIT, together with the controller hub model, texture and connection sounds.
This applies regardless of the copied/adapted code's current package or path;
the root reservation of rights applies only to Fire Control original work.

The existing source does not identify a complete per-line provenance map for
controller code, so this notice does not assign proprietary ownership to
untraced Drive-By-Wire-derived portions. API calls alone do not establish that
an entire integration class was copied from Drive-By-Wire.

See `src/main/resources/META-INF/notices/drivebywire-model.txt` and
`src/main/resources/META-INF/licenses/drivebywire-model-MIT.txt` for
attribution, the full license, exact asset paths and upstream sources. The JAR
also carries the broader code attribution in
`src/main/resources/META-INF/notices/drivebywire.txt`.

## Bundled audio notice

Tiger I exterior engine audio derives from user-supplied files matching
Gaijin's War Thunder FMOD asset directory. The asset-level notice is at
`src/main/resources/assets/create_fire_control/sounds/engine/tiger_i/NOTICE.md`.
Neither the Fire Control original-work license nor the MIT exceptions above
grant rights to those recordings, and independent Minecraft redistribution
permission has not been established there. Review that notice before publicly
redistributing the corresponding `.ogg` files.

## Other dependencies

Create, Sable, Create Aeronautics, Simulated, GeckoLib, Create Big Cannons,
面包军火库 (Mianbao's Modern Warfare) and other optional integrations are not
bundled by this repository. They retain their respective licenses and are not
relicensed by this notice. CopyPoly's MIT license does not cover the Fire
Control codebase.
