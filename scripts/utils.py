from pathlib import Path

script_path = Path(__file__).resolve().parent

# Mirrors the jar names generated from the `maf/build.sbt`, `assembleAll` task.
JAR_NAMES = [
        "oracle-lattice-generator.jar",
        "ml-oracle-finder.jar",
        "replay-lattice-generator.jar"
]

def path_to_jar(jar_name: str) -> Path:
    """
    Returns the path to the specified JAR file in the `maf/build` directory.
    """
    path_to_jar = script_path.parent / "maf" / "build" / jar_name
    return path_to_jar

def check_jar_exists(jar_name: str) -> bool:
    """
    Returns ture if the specified JAR file exists in the `maf/build` directory, otherwise returns false.
    """
    jar_path = path_to_jar(jar_name)
    return jar_path.exists()

def assert_all_jars_exist() -> None:
    """
    Asserts that the expected JAR files exists in the `maf/build  directory. Raises a FileNotFoundError if any of the JAR files are missing.
    """
    missing_jars = [jar_name for jar_name in JAR_NAMES if not check_jar_exists(jar_name)]
    if missing_jars:
        raise FileNotFoundError(f"Missing JAR files: {', '.join(missing_jars)}. Please run `sbt assembleAll` in the `maf` directory to generate them.")
