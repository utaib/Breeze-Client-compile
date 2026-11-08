# Third-party files

MCEF (the embedded browser), by CinemaMod Group, is licensed under the GNU
Lesser General Public License v2.1. The build fetches it from Modrinth's Maven
for the Minecraft versions it publishes a Fabric build for, to compile against
and for the development client; it is never part of Breeze's jar. The
real-install tests download the same file into `mods/`.
Source: https://github.com/CinemaMod/mcef

From Breeze 2.11.0, on Minecraft 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.10,
1.21.11, 26.1.1, 26.1.2, 26.2 and 26.3, the jar carries the embedded browser
inside it (a Fabric nested jar, unmodified), because the Breeze launcher
installs no browser on those versions. That browser is Keksuccino's
continuation of MCEF, published on Modrinth as Rinku (project `rinku`): its
MCEF 2.1.6, 2.1.7 and 2.2.0 builds and Rinku 3.0.4 and 3.0.5, licensed under
the GNU Lesser General Public License v2.1 or later. Which build each version
carries is `mcef_version` in `versions/<version>/gradle.properties`.
Source: https://github.com/Keksuccino/mcef
