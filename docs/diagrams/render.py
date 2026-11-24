"""Render the two fixed CommerceCore architecture layouts from editable JSON."""

import html
import json
from pathlib import Path


ROOT = Path(__file__).parent
COLORS = {"rest": "#2563eb", "grpc": "#7c3aed", "db": "#475569", "event": "#c2410c", "planned": "#0f766e"}


def render(path):
    data = json.loads(path.read_text())
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="900" viewBox="0 0 1440 900" role="img" aria-labelledby="title desc">',
             f'<title id="title">{html.escape(data["title"])}</title>',
             f'<desc id="desc">{html.escape(data["description"])}</desc>',
             '<rect width="1440" height="900" fill="#f8fafc"/>',
             '<defs>' + ''.join(f'<marker id="arrow-{kind}" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 1 1 L 9 5 L 1 9 z" fill="{color}"/></marker>' for kind, color in COLORS.items()) + '</defs>',
             '<style>text{font-family:Arial,Helvetica,sans-serif;fill:#0f172a}.title{font-size:30px;font-weight:700}.zone{font-size:17px;font-weight:700;fill:#334155}.node{font-size:16px;font-weight:700}.sub{font-size:12px;fill:#475569}.edge{font-size:12px;font-weight:700}.legend{font-size:13px}</style>',
             f'<text class="title" x="48" y="58">{html.escape(data["title"])}</text>',
             f'<text class="sub" x="48" y="83">{html.escape(data["subtitle"])}</text>']
    for zone in data["zones"]:
        x, y, w, h = zone["box"]
        parts += [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="{zone.get("fill", "#eef2f7")}" stroke="#cbd5e1"/>',
                  f'<text class="zone" x="{x + 18}" y="{y + 28}">{html.escape(zone["label"])}</text>']
    for edge in data["edges"]:
        points = " ".join(f'{x},{y}' for x, y in edge["points"])
        color = COLORS[edge["type"]]
        dash = ' stroke-dasharray="7 5"' if edge["type"] in ("event", "planned") else ""
        parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="2.5" marker-end="url(#arrow-{edge["type"]})"{dash}/>')
        x, y = edge["label_at"]
        parts.append(f'<text class="edge" x="{x}" y="{y}" fill="{color}">{html.escape(edge["label"])}</text>')
    for node in data["nodes"]:
        x, y, w, h = node["box"]
        parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="#fff" stroke="{node.get("stroke", "#64748b")}" stroke-width="2"/>')
        for i, line in enumerate(node["lines"]):
            cls = "node" if i == 0 else "sub"
            parts.append(f'<text class="{cls}" x="{x + 14}" y="{y + 27 + i * 20}">{html.escape(line)}</text>')
    parts.append('<rect x="40" y="840" width="1360" height="42" rx="8" fill="#e2e8f0"/>')
    legend = "Flow legend:  blue solid REST   ·   violet solid gRPC   ·   slate solid local DB   ·   orange dashed async/at least once   ·   teal dashed planned"
    parts.append(f'<text class="legend" x="58" y="866">{html.escape(legend)}</text></svg>')
    path.with_suffix(".svg").write_text("\n".join(parts) + "\n")


if __name__ == "__main__":
    for name in ("architecture.current", "architecture.future"):
        render(ROOT / f"{name}.json")
