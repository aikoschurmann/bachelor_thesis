from abc import ABC, abstractmethod
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Tuple, Sequence
import itertools
import os
import shutil
import subprocess
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
PIPELINE = SCRIPT_DIR / "lattice_pipeline.py"

# Defaults, overridable through --log-dir and --models-dir.
DEFAULT_LOG_DIR = PROJECT_ROOT / "logs"
DEFAULT_MODELS_DIR = PROJECT_ROOT / "models"

# Number of cores used by the phases that are allowed to use the whole machine.
MAX_CORES = os.cpu_count() or 1

# Set configurations to exclude 
EXCLUDE = set([
        "lattice_l2_b5_k0_fast",
        "lattice_l5_b5_k0_fast",
        "lattice_l15_b5_k0_fast",
        "lattice_l25_b5_k0_fast",
        "lattice_l2_b5_k1_fast",
        "lattice_l5_b5_k1_fast",
        "lattice_l15_b5_k1_fast",
        "lattice_l25_b5_k1_fast",
        "lattice_l2_b25_k0_all",
        "lattice_l5_b25_k0_all",
        "lattice_l15_b25_k0_all",
        "lattice_l25_b25_k0_all",
        "lattice_l2_b25_k1_all",
        "lattice_l5_b25_k1_all",
        "lattice_l15_b25_k1_all"
    ])

############################################################
# Infrastructure
############################################################

class Parameter(ABC): 
    @abstractmethod
    def values(self) -> Iterator[Tuple[str, Any]]:
        """
        A generator for the value of the parameter,
        generates tuples of the form (name, value)
        """
        pass 

    @abstractmethod
    def commandline_argument_name(self) -> str:
        """
        Returns the name of the command-line argument associated
        with the parameter
        """
        pass
    
    def construct_cli(self, _name: str, value: Any) -> list[str]: 
        """
        Constructs the command line arguments for a value (produced from "values()")
        for this parameter.

        Corresponds to the `command_line_argument_name` combined with a string
        representation of "values()" by default but can be overriden.
        """
        if value is None: 
            return []
        else:
            return [ self.commandline_argument_name(), str(value) ]

class ExtensionalParameter(Parameter): 
    """
    A parameter that originates from an extensional list 
    of values.
    """

    def __init__(self, values, argname, prefix: str | None = None): 
        self.__values = values
        self.argname = argname
        self.__prefix = prefix if prefix is not None else ""

    def values(self):
        return zip(map(lambda name: self.__prefix+str(name), self.__values), self.__values)

    def commandline_argument_name(self):
        return self.argname

class DictionaryParameter(Parameter):
    """
    A parameter whose values are given by the values
    in the dictionary.
    """

    def __init__(self, dict: Dict[str, Any], argname: str, keyargname: str | None = None): 
        self.__dict = dict
        self.argname = argname
        self.keyargname = keyargname

    def values(self): 
        for (name, value) in self.__dict.items():
            yield (name, value)

    def commandline_argument_name(self) -> str:
        return self.argname

    def construct_cli(self, name: str, value: Any) -> list[str]:
        val_arg = [ self.argname, str(value) ] if value is not None else []
        key_arg = [ self.keyargname , name ] if self.keyargname is not None else []
        return val_arg + key_arg

def cartesian(parameters: Sequence[Parameter]):
    """
    A combinator for generating combinations of parameters out of a 
    list of parameters.
    """
    parameter_values = map(lambda param: list(map(lambda tuple: (param, tuple), param.values())), parameters)
    return list(itertools.product(*parameter_values))

def id(x): 
    return x

def generate_cli(combinations: Sequence[Sequence[Tuple[Parameter, Tuple[str, Any]]]], map_name = id) -> Iterator[Tuple[str, Sequence[str]]]:
    """
    A generator for generating the command-line arguments for 
    each combination of the given parameters.
    """
    for configuration in combinations: 
        def get_name(configuration_param):
            (_, (name, _)) = configuration_param
            return str(name)
        def get_cli_param(configuration_param):
            (param, (name, value)) = configuration_param 
            return param.construct_cli(name, value)

        name = "_".join(list(map_name(map(get_name, configuration))))
        cli_params = list(itertools.chain.from_iterable(list(map(get_cli_param, configuration))))

        yield (name, cli_params)
    
############################################################
# Parameters
############################################################

FEATURE_SETS = {
        # The "all" feature set is explicitly set to "none"
        # so that no parameter is generated for the command
        "all": None, 
        "fast": ",".join([
            "was_selected",
            "is_main",
            "norm_pending_updates",
            "log_pending_updates",
            "norm_wait",
            "log_wait",
            "norm_size",
            "log_size",
            "norm_out_degree",
            "log_out_degree",
            "norm_in_degree",
            "log_in_degree",
            "norm_visits",
            "log_visits",
            "norm_age",
            "log_age",
            "norm_delta_change",
            "log_delta_change",
            "norm_avg_input_levelToTop",
            "log_avg_input_levelToTop",
            "norm_arity",
            "log_arity",
            "norm_avg_neighbor_conv",
            "log_avg_neighbor_conv"
            ])
        }


parameters = [
    ExtensionalParameter([2, 5, 15, 25], "--lookahead", "l"), 
    ExtensionalParameter([5, 25, 50], "--beam", "b"), 
    ExtensionalParameter([0, 1], "--k", "k"),
    DictionaryParameter(FEATURE_SETS, "--features", "--data-suffix")
    ]

def map_name(parameters):
    [l, b, k, features] = parameters 
    return [ "lattice", l, b, k, features ]

############################################################
# CPU isolation
############################################################

def _read(path: Path) -> Optional[str]:
    try:
        return path.read_text().strip()
    except OSError:
        return None


def physical_cores() -> List[int]:
    """
    Returns the list of CPU ids that correspond to distinct physical
    cores (i.e. only the first hyperthread sibling of each core). Falls
    back to all logical CPUs when the topology cannot be determined.
    """
    cpu_dir = Path("/sys/devices/system/cpu")
    if not cpu_dir.is_dir():
        return list(range(MAX_CORES))

    cores = []
    seen = set()
    for cpu in sorted(cpu_dir.glob("cpu[0-9]*"), key=lambda p: int(p.name[3:])):
        cpu_id = int(cpu.name[3:])
        siblings = _read(cpu / "topology" / "thread_siblings_list")
        key = siblings if siblings else str(cpu_id)
        if key in seen:
            continue
        seen.add(key)
        cores.append(cpu_id)
    return cores or list(range(MAX_CORES))


@contextmanager
def hyperthreading_disabled(enabled: bool = True):
    """
    Disables simultaneous multithreading for the duration of the block,
    restoring the previous setting afterwards. This only works on Linux
    and requires root privileges; in every other case a warning is
    printed and the machine is left untouched.
    """
    control = Path("/sys/devices/system/cpu/smt/control")
    previous = _read(control) if enabled else None

    if not enabled:
        pass
    elif previous is None:
        print("WARNING: cannot disable hyperthreading (no SMT control on this platform)")
    elif previous in ("off", "notsupported", "notimplemented"):
        previous = None  # nothing to restore
    else:
        try:
            control.write_text("off")
            print("Hyperthreading disabled for the duration of the benchmarks")
        except OSError as e:
            print(f"WARNING: cannot disable hyperthreading ({e}), run as root to enable this")
            previous = None

    try:
        yield
    finally:
        if previous is not None:
            try:
                control.write_text(previous)
                print(f"Hyperthreading setting restored to '{previous}'")
            except OSError as e:
                print(f"WARNING: could not restore hyperthreading setting ({e})")


def pinning_prefix(core: int) -> List[str]:
    """
    Returns the command prefix that pins a process to the given core, so
    that the measurements are not disturbed by the scheduler moving the
    process around. Returns an empty prefix when `taskset` is unavailable.
    """
    taskset = shutil.which("taskset")
    if taskset is None:
        print("WARNING: `taskset` is not available, benchmarks will not be pinned to a single core")
        return []
    return [taskset, "-c", str(core)]


############################################################
# Phases
############################################################

def run_command(command: Sequence[str], name: str, phase: str, log_dir: Path) -> bool:
    """
    Runs the given command, redirecting its stdout to <name>.<phase>.log
    and its stderr to <name>.<phase>.err inside the log directory.
    """
    log_dir.mkdir(parents=True, exist_ok=True)
    out_path = log_dir / f"{name}.{phase}.log"
    err_path = log_dir / f"{name}.{phase}.err"

    printable = " ".join(command)
    print(f">> [{phase}] {name}: {printable}")
    print(f"   stdout -> {out_path}")
    print(f"   stderr -> {err_path}")

    with out_path.open("w") as out, err_path.open("w") as err:
        out.write(f"# {printable}\n")
        out.flush()
        process = subprocess.Popen(command, cwd=PROJECT_ROOT, stdout=out, stderr=err)
        returncode = process.wait()

    if returncode != 0:
        print(f"ERROR: [{phase}] {name} failed with return code {returncode}, see {err_path}")
        return False
    return True


def pipeline_command(action: str, parameters: Sequence[str], model_dir: Path,
                     cores: int, prefix: Sequence[str] = ()) -> List[str]:
    """
    Builds the invocation of `lattice_pipeline.py` for the given action.
    Since the pipeline requires the non-default arguments to be repeated
    for every phase, the same `parameters` are passed to each action.
    """
    return [
        *prefix,
        sys.executable, str(PIPELINE),
        "--action", action,
        "--cores", str(cores),
        "--model-dir", str(model_dir),
        *parameters,
    ]


def data_collection_phase(name: str, parameters: Sequence[str], model_dir: Path,
                          log_dir: Path, core: int) -> bool:
    """
    Runs the data collection commands with the given list as parameters.
    """
    command = pipeline_command("generate", parameters, model_dir, cores=MAX_CORES)
    return run_command(command, name, "generate", log_dir)


def training_phase(name: str, parameters: Sequence[str], model_dir: Path,
                   log_dir: Path, core: int) -> bool:
    """
    Runs the training commands with the given list of parameters
    """
    # Run the training process, generate the Scala transpilation
    # of the resulting XGSBoosted machine, and compile.
    model_dir.mkdir(parents=True, exist_ok=True)
    command = pipeline_command("train", parameters, model_dir, cores=MAX_CORES)
    return run_command(command, name, "train", log_dir)


def benchmark_phase(name: str, parameters: Sequence[str], model_dir: Path,
                    log_dir: Path, core: int) -> bool:
    """
    Runs the benchmarking commands with the given list of parameters
    """
    # Benchmarks are single threaded and pinned to a single (physical) core
    # so that the reported timings are as free of noise as possible.
    command = pipeline_command("evaluate", parameters, model_dir, cores=1, prefix=pinning_prefix(core))
    return run_command(command, name, "evaluate", log_dir)


def all_phases(name: str, parameters: Sequence[str], model_dir: Path,
               log_dir: Path, core: int) -> bool:
    """
    Runs all phases.
    """
    return (data_collection_phase(name, parameters, model_dir, log_dir, core)
            and training_phase(name, parameters, model_dir, log_dir, core)
            and benchmark_phase(name, parameters, model_dir, log_dir, core))


############################################################
# Run
############################################################

import argparse

PHASES = {
    "generate": data_collection_phase,
    "train": training_phase,
    "evaluate": benchmark_phase,
    "all": all_phases,
}


def main():
    parser = argparse.ArgumentParser(description="Runs the full pipeline for every configuration")
    parser.add_argument("--phase", choices=sorted(PHASES.keys()), default="all",
                        help="The phase to run for each configuration (default: all)")
    parser.add_argument("--log-dir", type=Path, default=DEFAULT_LOG_DIR,
                        help=f"Directory for the .log/.err files (default: {DEFAULT_LOG_DIR})")
    parser.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS_DIR,
                        help=f"Directory in which a per-configuration model directory is created (default: {DEFAULT_MODELS_DIR})")
    parser.add_argument("--core", type=int, default=None,
                        help="The core to pin the benchmarks to (default: the last physical core)")
    parser.add_argument("--no-disable-smt", action="store_true",
                        help="Do not attempt to disable hyperthreading during the benchmark phase")
    parser.add_argument("--keep-going", action="store_true",
                        help="Continue with the next configuration when one fails")
    parser.add_argument("--dry-run", action="store_true",
                        help="Only print the configurations that would be run")
    args = parser.parse_args()

    combinations = cartesian(parameters)
    configurations = list(generate_cli(combinations, map_name))
    core = args.core if args.core is not None else physical_cores()[-1]

    if args.dry_run:
        for (name, cli_params) in configurations:
            if name in EXCLUDE:
                continue
            print(f"{name}: {' '.join(cli_params)}")
        return 0

    run_phase = PHASES[args.phase]
    # Hyperthreading only influences the phases that actually measure time.
    disable_smt = not args.no_disable_smt and args.phase in ("evaluate", "all")

    failed = []
    with hyperthreading_disabled(disable_smt):
        for (i, (name, cli_params)) in enumerate(configurations, start=1):
            print(f"\n{'='*60}\n [{i}/{len(configurations)}] {name}\n{'='*60}")
            if name in EXCLUDE:
                print("SKIPPING")
                continue

            model_dir = args.models_dir / name
            if not run_phase(name, cli_params, model_dir, args.log_dir, core):
                failed.append(name)
                if not args.keep_going:
                    break

    if failed:
        print(f"\nFAILED configurations ({len(failed)}): {', '.join(failed)}")
        return 1

    print(f"\nCompleted {len(configurations)} configurations successfully")
    return 0


if __name__ == "__main__":
    sys.exit(main())
