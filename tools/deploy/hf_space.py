"""Builds and publishes the one-container Hugging Face Space (spec 11, task 15).

    python -I tools/deploy/hf_space.py build              # app.jar and html/ from the normal Dockerfiles -> local/hf-space
    python -I tools/deploy/hf_space.py test               # build the Space image here and run it on http://127.0.0.1:7860
    python -I tools/deploy/hf_space.py upload <user>/<space> [--claude]

`upload` needs a Hugging Face token with write access in the HF_TOKEN environment variable (or a saved
`huggingface-cli login`). With --claude, the AI_API_KEY from the local .env file is stored as a Space secret, which reaches
only the container; the analyst then uses Claude with the $2 daily cap. The key is never printed or uploaded as a file.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / "local" / "hf-space"
IMAGE = "realitycheck-space"


def run(*cmd: str) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()


def copy_out(context: str, path: str, dest: Path) -> None:
    """Builds a Dockerfile's `build` stage and copies one path out of it."""
    tag = f"realitycheck-{context}-build"
    print(f"building {context} ...")
    subprocess.run(["docker", "build", "-q", "--target", "build", "-t", tag, str(ROOT / context)], check=True,
                   stdout=subprocess.DEVNULL)
    cid = run("docker", "create", tag)
    try:
        run("docker", "cp", f"{cid}:{path}", str(dest))
    finally:
        run("docker", "rm", cid)


def build() -> None:
    if STAGE.exists():
        shutil.rmtree(STAGE)
    STAGE.mkdir(parents=True)
    copy_out("backend", "/workspace/target/realitycheck-backend.jar", STAGE / "app.jar")
    copy_out("frontend", "/app/dist", STAGE / "html")
    # Text files go up with Unix line endings, whatever the working copy uses (start.sh must not contain CR).
    sources = list((ROOT / "deploy" / "huggingface").iterdir()) + [ROOT / "frontend" / "nginx-proxy.conf"]
    for f in sources:
        (STAGE / f.name).write_bytes(f.read_bytes().replace(b"\r\n", b"\n"))
    size = sum(p.stat().st_size for p in STAGE.rglob("*") if p.is_file()) / 1e6
    print(f"staged {STAGE} ({size:.0f} MB)")


def test() -> None:
    print("building the Space image ...")
    subprocess.run(["docker", "build", "-q", "-t", IMAGE, str(STAGE)], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["docker", "rm", "-f", IMAGE], capture_output=True)
    run("docker", "run", "-d", "--name", IMAGE, "--user", "1000", "-p", "127.0.0.1:7860:7860",
        "-e", "CORS_ALLOWED_ORIGINS=http://127.0.0.1:7860", IMAGE)
    start = time.time()
    while time.time() - start < 240:
        try:
            with urllib.request.urlopen("http://127.0.0.1:7860/api/system/info", timeout=3) as r:
                print(f"up after {time.time() - start:.0f} s: {r.read(160).decode()}")
                return
        except OSError:
            time.sleep(3)
    print(run("docker", "logs", "--tail", "40", IMAGE))
    sys.exit("the Space image did not come up")


def env_value(name: str) -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    return ""


def upload(space: str, claude: bool) -> None:
    from huggingface_hub import HfApi

    api = HfApi()
    api.create_repo(space, repo_type="space", space_sdk="docker", exist_ok=True)
    if claude:
        key = env_value("AI_API_KEY")
        if not key:
            sys.exit("no AI_API_KEY in .env")
        api.add_space_secret(space, "AI_API_KEY", key)
        api.add_space_variable(space, "AI_MODEL", env_value("AI_MODEL") or "claude-haiku-5-5")
        api.add_space_variable(space, "AI_DAILY_BUDGET_USD", "2")
        print("Claude key stored as a Space secret; daily cap $2")
    api.upload_folder(folder_path=str(STAGE), repo_id=space, repo_type="space",
                      commit_message="Deploy RealityCheck")
    host = space.replace("/", "-").replace("_", "-").lower()
    print(f"uploaded. Page: https://huggingface.co/spaces/{space}   App: https://{host}.hf.space")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "build":
        build()
    elif command == "test":
        test()
    elif command == "upload" and len(sys.argv) > 2:
        upload(sys.argv[2], "--claude" in sys.argv)
    else:
        sys.exit(__doc__)
