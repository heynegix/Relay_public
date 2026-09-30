"""Check source exports; supply private deny terms outside the repository."""
import argparse
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--deny-file', type=Path)
args = parser.parse_args()
terms = args.deny_file.read_text(encoding='utf-8').splitlines() if args.deny_file else []
terms = [term for term in terms if term.strip()]
patterns = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'GitHub credential': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'AWS access key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
}
allowed_images = {
    'pc-ble-bridge/Assets/Square150x150Logo.png',
    'pc-ble-bridge/Assets/Square44x44Logo.png',
    'pc-ble-bridge/Assets/StoreLogo.png',
}
ignored = {'.git', '.gradle', '.kotlin', 'build', 'node_modules', '__pycache__', 'bin', 'obj'}
failures = []
count = 0
for path in sorted(args.root.rglob('*')):
    relative = path.relative_to(args.root)
    if not path.is_file() or any(part in ignored for part in relative.parts):
        continue
    name = relative.as_posix()
    count += 1
    if path.is_symlink():
        failures.append((name, 'symlink requires review'))
        continue
    if path.suffix.lower() in {'.jks', '.keystore', '.pfx', '.p12', '.pem', '.key', '.pkcs8', '.apk', '.aab', '.exe', '.db', '.log'} or path.name == '.env':
        failures.append((name, 'local or sensitive file'))
    raw = path.read_bytes()
    for term in terms:
        if term.casefold() in name.casefold() or term.casefold() in raw.decode('utf-8', errors='ignore').casefold():
            failures.append((name, 'private deny term'))
            break
    try:
        content = raw.decode('utf-8')
    except UnicodeDecodeError:
        if name not in allowed_images:
            failures.append((name, 'unreviewed binary'))
        continue
    for label, pattern in patterns.items():
        if pattern.search(content):
            failures.append((name, label))
if failures:
    for name, reason in failures:
        print(f'{name}: {reason}')
    raise SystemExit(1)
print(f'Public source checks passed: {count} files. Run gitleaks separately for broader secret detection.')
