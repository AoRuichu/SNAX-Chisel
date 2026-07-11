// mx_like_simple_core — differentiation-preserving DPU template.
// Lutz EA architecture (ARITH 2024), per-config tailored widths, single-cycle
// combinational datapath (no internal pipelining), BF16 accumulator.

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "be.kuleuven.esat.micas"

val chiselVersion = "6.4.0"

lazy val root = (project in file("."))
  .settings(
    name := "mx-like-simple-core",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"     % chiselVersion,
      "edu.berkeley.cs"   %% "chiseltest" % "6.0.0" % Test,
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    )
  )
