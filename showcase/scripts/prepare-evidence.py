"""Prepare only the three hash-pinned, locally reviewed examples. Never deploys."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SHOWCASE = ROOT / 'showcase'
sys.path.insert(0, str(ROOT / 'tools/consistency_lab'))
import debugger
import replay


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def privacy(raw):
    text = raw.decode('utf-8')
    if re.search(r'(?i)(/Users/|/home/|localhost|127\.0\.0\.1|https?://|postgres(?:ql)?://|password|secret|token|@|BEGIN .*PRIVATE KEY)', text):
        raise ValueError('candidate contains a forbidden privacy marker')


def witness(history, result):
    if not result.get('witness'):
        return
    positions = {op: i for i, op in enumerate(result['witness'])}
    assert len(positions) == history['plannedCount']
    for i, a in enumerate(history['plan']['operations']):
        for j, b in enumerate(history['plan']['operations']):
            x, y = history['observations'][i], history['observations'][j]
            if (a['actorId'] == b['actorId'] and a['step'] < b['step']) or (x['completedNanos'] is not None and y['invokedNanos'] is not None and x['completedNanos'] < y['invokedNanos']):
                assert positions[a['operationId']] < positions[b['operationId']]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--checkout', type=Path, required=True)
    parser.add_argument('--synthetic', type=Path, required=True)
    args = parser.parse_args()
    plan = json.loads((SHOWCASE / 'evidence/approved-inputs.json').read_text())
    entries = []
    outputs = {}
    revision = '8c7a59a0c025ab7121780e4df5b742fc9d7838cf'
    with tempfile.TemporaryDirectory(prefix='commercecore-c3-') as temporary:
        for id in ('inventory', 'checkout', 'synthetic'):
            source = getattr(args, id).resolve()
            files = plan[id]
            expected_names = set(files)
            if id != 'checkout' and {p.name for p in source.iterdir()} != expected_names:
                raise ValueError('unexpected files in explicit bundle input')
            raw_files = {}
            for name, expected in files.items():
                path = source if id == 'checkout' else source / name
                if path.is_symlink() or path.stat().st_size > replay.MAX_BYTES:
                    raise ValueError('unsafe input file')
                raw = path.read_bytes()
                if sha(raw) != expected:
                    raise ValueError('input hash mismatch: ' + id + '/' + name)
                privacy(raw)
                raw_files[name] = raw
            bundle = Path(temporary) / id
            if id == 'checkout':
                replay.create(source, bundle, source_kind='captured_test_execution', exclusive_scope=True)
            else:
                bundle.mkdir()
                for name, raw in raw_files.items():
                    (bundle / name).write_bytes(raw)
            result = replay.recheck(bundle)
            payload = debugger.evidence(bundle)  # Also verifies retained operations and reduction metadata.
            history = payload['history']
            witness(history, result)
            if payload['reduced']:
                witness(payload['reduced']['history'], payload['reduced']['result'])
            expected_kind = 'synthetic_mutation' if id == 'synthetic' else 'captured_test_execution'
            assert payload['source'] == expected_kind
            assert result['verdict'] == ('FAIL' if id == 'synthetic' else 'PASS')
            original_html = debugger.render(bundle)
            style = re.search(r'<style>(.*?)</style>', original_html, re.S).group(1)
            script = re.search(r'<script>(.*?)</script>', original_html, re.S).group(1)
            original_script = script
            if id == 'synthetic':
                script = script.replace('Observed return or rejection only; no internal linearization point was recorded.', 'Synthetic modeled outcome; no actual service call was executed.')
                for old, new in [
                    ('meta("Clock",data.history.clockSource);', 'meta("Clock",data.history.clockSource+" — Inherited fixture metadata; synthetic intervals are constructed, not measured JVM timing.");meta("Topology",data.history.provenance+" — Inherited fixture metadata; does not describe an observed execution of this synthetic history.");'),
                    ('result.scope', '(result.scope+" — Inherited checker-contract scope label; does not mean these synthetic calls occurred in CommerceCore.")'),
                ]:
                    assert script.count(old) == 1, 'synthetic presentation source changed'
                    script = script.replace(old, new)
            banner = '<section class="note"><strong>Local review candidate — deployment approval pending.</strong> ' + ('Synthetic checker mutation; not an observed CommerceCore defect. Reduced history was not executed.' if id == 'synthetic' else 'Historical observed test history; capture Git revision UNVERIFIED. Tool revision: ' + revision + '.') + ' <a href="/lab/debugger">Choose another example</a> · <a href="/evidence">Provenance and limitations</a></section>'
            html = original_html.replace('<title>', '<link rel="icon" href="/reports/favicon.svg"><title>').replace('<style>'+style+'</style>', '<link rel="stylesheet" href="/reports/debugger.css">').replace('<script>'+original_script+'</script>', '<script src="/reports/'+id+'.js" defer></script>').replace('<main class="shell">', '<main class="shell">'+banner)
            if id == 'synthetic':
                html = html.replace('>Observed execution</h2>', '>Synthetic operation intervals</h2>').replace('Mandatory observed ordering', 'Model-required ordering').replace('Observed precedence:', 'Model precedence:').replace('Offline view of recorded service calls and conditional checker explanations.', 'Offline view of constructed synthetic calls and analytical checker explanations.')
                timing = 'Horizontal position is the M18 test JVM monotonic clock; it is not a database commit time or linearization point.'
                assert html.count(timing) == 1, 'synthetic timing source changed'
                html = html.replace(timing, 'Horizontal positions represent constructed intervals in this synthetic checker fixture. They are not measured JVM execution timestamps.').replace('Completed interval', 'Constructed interval')
            assert '<script>' not in html and '<style>' not in html, 'inline code must be externalized'
            assets = {f'reports/{id}.html': html.encode(), f'reports/{id}.js': script.encode(), 'reports/debugger.css': style.encode(), 'reports/favicon.svg': b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="4" fill="#08090b"/><text x="4" y="21" fill="#f2f3f5" font-family="monospace" font-size="16">CC</text></svg>'}
            for path, raw in assets.items():
                if path in outputs:
                    assert outputs[path] == raw
                outputs[path] = raw
            bundle_hashes = {}
            for path in sorted(bundle.iterdir()):
                raw = path.read_bytes()
                privacy(raw)
                dest = SHOWCASE / 'evidence/inputs' / id / path.name
                outputs[str(dest.relative_to(SHOWCASE))] = raw
                bundle_hashes[path.name] = sha(raw)
            entries.append({
                'id': id, 'title': {'inventory': 'Observed inventory reservations', 'checkout': 'Observed checkout retries', 'synthetic': 'Synthetic oversell counterexample'}[id],
                'sourceKind': expected_kind, 'scenario': history['plan']['scenarioId'], 'property': result['property'],
                'verdict': result['verdict'], 'operations': history['plannedCount'], 'runId': history['plan']['runId'], 'schemaVersion': history['schemaVersion'],
                'captureDate': history['startedAt'][:10], 'captureRevision': None, 'captureRevisionStatus': 'UNVERIFIED — not recorded by M18/M20',
                'toolRevision': revision, 'documentedQualificationRevision': '6c164a0c1adbc1dd03662afa0f1f5b881f6b3ba3',
                'provenance': history['provenance'], 'qualification': ('Synthetic fixture / fresh offline C3 recheck; never an observed application execution.' if id == 'synthetic' else 'Historical local capture / fresh offline C3 recheck; no hosted execution attestation for these exact bytes.'),
                'sourceFiles': ['build/consistency-lab/' + ('m18/checkout_retry.json' if id == 'checkout' else ('m22-synthetic/' if id == 'synthetic' else 'm22-observed/')+name) for name in files],
                'sourceHashes': files, 'historySha256': replay.digest((bundle/'history.json').read_bytes()),
                'checkerSha256': json.loads((bundle/'manifest.json').read_text())['checker_sha256'],
                'assumptions': ['Exclusive target scope', 'No unrecorded writers', 'Logical identities match actual calls'],
                'recordedResult': result, 'bundleHashes': bundle_hashes,
                'report': '/reports/'+id, 'reportSha256': sha(html.encode()), 'generatedHtmlSha256': sha(original_html.encode()),
                'assetHashes': {path: sha(raw) for path, raw in assets.items()},
                'changes': 'Original history/bundle bytes retained. Checkout receives a new M20 manifest using an external exclusive-scope declaration. Generated M21 HTML externalizes script/style and adds a provenance banner and local CC favicon. Synthetic derivative labels say modeled rather than observed; no history redaction.' + (' C3.1 qualifies inherited clock, topology and checker-scope values as fixture metadata; intervals are constructed, not measured.' if id == 'synthetic' else ''),
                'limitation': ('Synthetic original and 4→2 analytical reduction qualify the checker; neither was an observed CommerceCore defect.' if id == 'synthetic' else 'Historical test provenance is self-reported; capture revision and producer authentication remain unverified. PASS is a possible sequential explanation, not persisted-state proof.'),
                'reproduce': 'PYTHONDONTWRITEBYTECODE=1 python3 tools/consistency_lab/replay.py recheck showcase/evidence/inputs/'+id,
                'ownerDeploymentApproval': 'PENDING'
            })
    # No destination is written until all three inputs pass the existing verification tools.
    for path, raw in outputs.items():
        dest = SHOWCASE/path if path.startswith('evidence/') else SHOWCASE/'public'/path
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.exists() or dest.read_bytes() != raw:
            dest.write_bytes(raw)
    catalog = SHOWCASE/'evidence/catalog.json'
    raw_catalog = (json.dumps(entries, indent=2)+'\n').encode()
    if not catalog.exists() or catalog.read_bytes() != raw_catalog:
        catalog.write_bytes(raw_catalog)
    print(json.dumps([{'id': e['id'], 'verdict': e['verdict'], 'operations': e['operations'], 'source': e['historySha256'], 'report': e['reportSha256']} for e in entries], indent=2))


if __name__ == '__main__':
    main()
