package com.aspix2k.affected.build

import com.aspix2k.affected.build.cargo.CargoBuildSystem
import com.aspix2k.affected.build.cmake.CMakeBuildSystem
import com.aspix2k.affected.build.dart.DartBuildSystem
import com.aspix2k.affected.build.dart.FlutterBuildSystem
import com.aspix2k.affected.build.deno.DenoBuildSystem
import com.aspix2k.affected.build.dotnet.DotnetBuildSystem
import com.aspix2k.affected.build.go.GoBuildSystem
import com.aspix2k.affected.build.node.NodeBuildSystem
import com.aspix2k.affected.build.php.ComposerBuildSystem
import com.aspix2k.affected.build.python.PythonBuildSystem
import com.aspix2k.affected.build.ruby.RubyBuildSystem
import com.aspix2k.affected.build.xcode.XcodeBuildSystem

class IdeCargoBuildSystem : IdeBuildSystem(CargoBuildSystem())
class IdeGoBuildSystem : IdeBuildSystem(GoBuildSystem())
class IdeNodeBuildSystem : IdeBuildSystem(NodeBuildSystem())
class IdeDotnetBuildSystem : IdeBuildSystem(DotnetBuildSystem())
class IdePythonBuildSystem : IdeBuildSystem(PythonBuildSystem())
class IdeComposerBuildSystem : IdeBuildSystem(ComposerBuildSystem())
class IdeRubyBuildSystem : IdeBuildSystem(RubyBuildSystem())
class IdeRBuildSystem : IdeBuildSystem(RBuildSystem())
class IdeCMakeBuildSystem : IdeBuildSystem(CMakeBuildSystem())
class IdeSbtBuildSystem : IdeBuildSystem(SbtBuildSystem())
class IdeAntBuildSystem : IdeBuildSystem(AntBuildSystem())
class IdeKotlinToolchainBuildSystem : IdeBuildSystem(KotlinToolchainBuildSystem())
class IdeBazelBuildSystem : IdeBuildSystem(BazelBuildSystem())
class IdeDartBuildSystem : IdeBuildSystem(DartBuildSystem())
class IdeFlutterBuildSystem : IdeBuildSystem(FlutterBuildSystem())
class IdeMesonBuildSystem : IdeBuildSystem(MesonBuildSystem())
class IdeMakeBuildSystem : IdeBuildSystem(MakeBuildSystem())
class IdeNinjaBuildSystem : IdeBuildSystem(NinjaBuildSystem())
class IdePantsBuildSystem : IdeBuildSystem(PantsBuildSystem())
class IdeBuck2BuildSystem : IdeBuildSystem(Buck2BuildSystem())
class IdeSwiftBuildSystem : IdeBuildSystem(SwiftBuildSystem())
class IdeXcodeBuildSystem : IdeBuildSystem(XcodeBuildSystem())
class IdeDbtBuildSystem : IdeBuildSystem(DbtBuildSystem())
class IdeSqlcBuildSystem : IdeBuildSystem(SqlcBuildSystem())
class IdeAtlasBuildSystem : IdeBuildSystem(AtlasBuildSystem())
class IdeDenoBuildSystem : IdeBuildSystem(DenoBuildSystem())
