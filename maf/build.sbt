import sbt.Keys.libraryDependencies
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

lazy val root = project
  .in(file("."))
  .aggregate(mafJVM, mafJS)

lazy val maf = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("code"))
  .settings(
    /** General settings */
    name := "maf",
    organization := "soft",
    version := "2.0",
    scalaVersion := "3.3.7",
    //crossScalaVersions ++= Seq("2.13.6", "3.1.0"),
    /** Dependencies */
    libraryDependencies += "org.scala-lang.modules" %%% "scala-parser-combinators" % "2.0.0",
    libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
    libraryDependencies += "com.opencsv" % "opencsv" % "5.5.2",
    libraryDependencies += ("com.typesafe.akka" %% "akka-actor-typed" % "2.6.18").cross(CrossVersion.for3Use2_13),
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.10",
    libraryDependencies += "com.typesafe" % "config" % "1.4.1",

    libraryDependencies += "ml.dmlc" % "xgboost4j_2.13" % "2.1.0",
    
    /** Compilation options */
    maxErrors := 5,
    /** Configuration for running the tests */
    Test / logBuffered := false,
    Test / testOptions += Tests.Argument("-oI"), // Produces a summary after running the tests, showing the failing tests
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % "test",
    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.15.4" % "test",
    libraryDependencies += "org.scalatestplus" %% "scalacheck-1-15" % "3.2.9.0" % "test",
    libraryDependencies += "com.vladsch.flexmark" % "flexmark-all" % "0.62.2" % Test,
    resolvers += "bramvdbogaerde" at "https://raw.githubusercontent.com/bramvdbogaerde/maven-repo/master",
    libraryDependencies += ("space.vdb" %% "scala-smtlib" % "0.4.4"),
    libraryDependencies ++= Seq(
       "dev.optics" %% "monocle-core"  % "3.1.0",
       "dev.optics" %% "monocle-macro" % "3.1.0",
      ),
    /** Imported options from https://tpolecat.github.io/2017/04/25/scalac-flags.html */
    scalacOptions ++= Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      //"-encoding",
      // "-explain",
      "-new-syntax",
      "-Yexplicit-nulls",
      //"-explaintypes", // Explain type errors in more detail.
      "-feature", // Emit warning and location for usages of features that should be imported explicitly.
      "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
      "-language:experimental.macros", // Allow macro definition (besides implementation and application)
      "-language:higherKinds", // Allow higher-kinded types
      "-language:implicitConversions", // Allow definition of implicit functions called views
      "-unchecked", // Enable additional warnings where generated code depends on assumptions.
      //"-Ycheck-init", // Wrap field accessors to throw an exception on uninitialized access.
      //"-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
      //"-Xfuture",                          // Turn on future language features.
      //"-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
      //"-Xlint:by-name-right-associative",  // By-name parameter of right associative operator.
      //"-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
      //"-Xlint:delayedinit-select", // Selecting member of DelayedInit.
      //"-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
      //"-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
      //"-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
      //"-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
      //"-Xlint:nullary-unit", // Warn when nullary methods return Unit.
      //"-Xlint:option-implicit", // Option.apply used implicit view.
      //"-Xlint:package-object-classes", // Class or object defined in package object.
      //"-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
      //"-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
      //"-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
      //"-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
      //"-Xlint:unsound-match",              // Pattern match may not be typesafe.
      //"-Ypartial-unification",             // Enable partial unification in type constructor inference
      //"-Ywarn-dead-code", // Warn when dead code is identified.
      //"-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
      //"-Ywarn-inaccessible",               // Warn about inaccessible types in method signatures.
      //"-Ywarn-infer-any",                  // Warn when a type argument is inferred to be `Any`.
      //"-Ywarn-nullary-override",           // Warn when non-nullary `def f()' overrides nullary `def f'.
      //"-Ywarn-nullary-unit",               // Warn when nullary methods return Unit.
      //"-Ywarn-numeric-widen", // Warn when numerics are widened.
      //"-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
      //"-Ywarn-unused:imports", // Warn if an import selector is not referenced.
      //"-Ywarn-unused:locals", // Warn if a local definition is unused.
      //"-Ywarn-unused:params",              // Warn if a value parameter is unused.
      //"-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
      //"-Ywarn-unused:privates" // Warn if a private member is unused.
      // "-Ywarn-value-discard"               // Warn when non-Unit expression results are unused.
    )
  )
  .jvmSettings(
    /** General */
    Compile / mainClass := Some("maf.cli.Main"),
    //libraryDependencies += "net.openhft" % "affinity" % "3.21ea82",
    run / fork := false,
  )
  .jvmConfigure(_.enablePlugins(JmhPlugin))
  .jsSettings(
    /** Dependencies */
    libraryDependencies += ("org.scala-js" %%% "scalajs-dom" % "1.1.0").cross(CrossVersion.for3Use2_13)
  )

lazy val mafJVM = maf.jvm
lazy val mafJS = maf.js

/** Shared assembly settings: how to resolve duplicate files coming from the fat jar's dependencies. */
lazy val assemblySettings = Seq(
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "services", _*)              => MergeStrategy.filterDistinctLines
    case PathList("META-INF", "MANIFEST.MF")               => MergeStrategy.discard
    case PathList("META-INF", xs @ _*) if xs.lastOption.exists(n =>
          n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")) =>
      MergeStrategy.discard
    case PathList("reference.conf")                        => MergeStrategy.concat
    case PathList("application.conf")                      => MergeStrategy.concat
    case PathList("module-info.class")                     => MergeStrategy.discard
    case x if x.endsWith("/module-info.class")             => MergeStrategy.discard
    case x                                                 => MergeStrategy.first
  },
  // Native libraries (e.g. those shipped by xgboost4j) must stay in the jar.
  assembly / assemblyOption ~= { _.withIncludeScala(true).withIncludeDependency(true) },
  assembly / test := {}
)

/** Copies the assembled fat jar into maf/build/<jarName>. */
lazy val buildJar = taskKey[File]("Assemble the fat jar and copy it into the build/ directory")

/** Creates a project that only exists to produce a fat jar for `main`, named `jar`. */
def fatJar(id: String, main: String, jar: String): Project =
  Project(id, file("target/assembly") / id)
    .dependsOn(mafJVM)
    .settings(assemblySettings)
    .settings(
      scalaVersion := "3.3.7",
      publish / skip := true,
      Compile / mainClass := Some(main),
      assembly / mainClass := Some(main),
      assembly / assemblyJarName := jar,
      buildJar := {
        val source = assembly.value
        val target = (ThisBuild / baseDirectory).value / "build" / jar
        IO.copyFile(source, target)
        streams.value.log.info(s"Copied $source to $target")
        target
      }
    )

/** Used by scripts/lattice_pipeline.py */
lazy val oracleLatticeGenerator =
  fatJar("oracleLatticeGenerator", "maf.cli.runnables.OracleLatticeGenerator", "oracle-lattice-generator.jar")

/** Used by scripts/lattice_pipeline.py */
lazy val mlOracleFinder =
  fatJar("mlOracleFinder", "maf.cli.runnables.MLOracleFinder", "ml-oracle-finder.jar")

/** Used by scripts/lattice_pipeline.py */
lazy val replayLatticeGenerator =
  fatJar("replayLatticeGenerator", "maf.cli.runnables.ReplayLatticeGenerator", "replay-lattice-generator.jar")

lazy val randomTrajectoryGenerator =
  fatJar("randomTrajectoryGenerator", "maf.cli.runnables.RandomTrajectoryGenerator", "random-trajectory-generator.jar")

/** Builds all three fat jars at once and places them in maf/build/. */
lazy val assembleAll = taskKey[Seq[File]]("Assemble all runnable fat jars into build/")
assembleAll := Seq(
  (oracleLatticeGenerator / buildJar).value,
  (mlOracleFinder / buildJar).value,
  (replayLatticeGenerator / buildJar).value,
  (randomTrajectoryGenerator / buildJar).value
)
