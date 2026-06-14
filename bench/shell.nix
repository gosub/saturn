# Dev shell for the Saturn model benchmark (separate from the Android app's
# shell.nix). Provides a Python interpreter; third-party deps live in a local
# .venv created from requirements.txt, never installed globally.
{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = [ pkgs.python3 ];

  shellHook = ''
    if [ ! -d .venv ]; then
      python3 -m venv .venv
      .venv/bin/pip install --quiet --upgrade pip
      .venv/bin/pip install --quiet -r requirements.txt
    fi
    source .venv/bin/activate
    echo "saturn-bench shell: run 'python -m saturn_bench.runner --selftest'"
  '';
}
