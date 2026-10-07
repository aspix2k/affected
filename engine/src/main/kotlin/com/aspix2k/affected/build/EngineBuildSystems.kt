package com.aspix2k.affected.build

import com.aspix2k.affected.build.cargo.CargoBuildSystem
import com.aspix2k.affected.build.cmake.CMakeBuildSystem
import com.aspix2k.affected.build.dart.DartBuildSystem
import com.aspix2k.affected.build.dart.FlutterBuildSystem
import com.aspix2k.affected.build.deno.DenoBuildSystem
import com.aspix2k.affected.build.dotnet.DotnetBuildSystem
import com.aspix2k.affected.build.go.GoBuildSystem
import com.aspix2k.affected.build.gradle.GradleCommandLineBuildSystem
import com.aspix2k.affected.build.maven.MavenCommandLineBuildSystem
import com.aspix2k.affected.build.node.NodeBuildSystem
import com.aspix2k.affected.build.php.ComposerBuildSystem
import com.aspix2k.affected.build.python.PythonBuildSystem
import com.aspix2k.affected.build.ruby.RubyBuildSystem
import com.aspix2k.affected.build.xcode.XcodeBuildSystem

internal object EngineBuildSystems {

    fun all(): List<EngineBuildSystem> = adapters() + GradleCommandLineBuildSystem() + MavenCommandLineBuildSystem()

    fun adapters(): List<EngineBuildSystem> = listOf(
        CargoBuildSystem(),
        GoBuildSystem(),
        NodeBuildSystem(),
        DotnetBuildSystem(),
        PythonBuildSystem(),
        ComposerBuildSystem(),
        RubyBuildSystem(),
        RBuildSystem(),
        CMakeBuildSystem(),
        SbtBuildSystem(),
        AntBuildSystem(),
        KotlinToolchainBuildSystem(),
        BazelBuildSystem(),
        DartBuildSystem(),
        FlutterBuildSystem(),
        MesonBuildSystem(),
        MakeBuildSystem(),
        NinjaBuildSystem(),
        PantsBuildSystem(),
        Buck2BuildSystem(),
        SwiftBuildSystem(),
        XcodeBuildSystem(),
        DbtBuildSystem(),
        SqlcBuildSystem(),
        AtlasBuildSystem(),
        DenoBuildSystem(),
    )
}
