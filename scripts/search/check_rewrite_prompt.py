#!/usr/bin/env python3
"""운영 질의 해석 프롬프트(Kotlin QueryRewriter.PROMPT_PREFIX)가 평가 도구의 프롬프트와 글자 하나까지 같은지 확인한다.

평가(search-eval.mjs --rewrite)가 재는 프롬프트와 운영이 쓰는 프롬프트가 조용히 갈라지지 않게 하는 점검이다.
사용: python3 scripts/search/check_rewrite_prompt.py <평가 도구의 rewrite.py>   (종료 코드 0 = 같음, 1 = 다름)
키·네트워크는 쓰지 않는다.
"""
import ast
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "src/main/kotlin/com/richjun/liftupai/domain/workout/search/rewrite/QueryRewriter.kt"


def kotlin_prompt() -> str:
    src = KOTLIN.read_text(encoding="utf-8")
    m = re.search(r"const val PROMPT_PREFIX = (.*?)\n\n", src, re.S)
    if not m:
        raise SystemExit("PROMPT_PREFIX not found in QueryRewriter.kt")
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    return "".join(ast.literal_eval('"' + p + '"') for p in parts)


def python_prompt(path: str) -> str:
    tree = ast.parse(pathlib.Path(path).read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "PROMPT" for t in node.targets):
            return ast.literal_eval(node.value)
    raise SystemExit("PROMPT not found in " + path)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    a, b = kotlin_prompt(), python_prompt(sys.argv[1])
    if a == b:
        print("REWRITE_PROMPT_SAME", len(a))
    else:
        print("REWRITE_PROMPT_DIFFERENT")
        print("kotlin:", repr(a))
        print("python:", repr(b))
        sys.exit(1)
