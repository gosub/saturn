"""OpenRouter HTTP client + free-model discovery.

chat() reproduces the state machine of AgentClient.chat
(app/src/main/java/it/lo/exp/saturn/AgentClient.java:97-178): same endpoint,
headers, response_format=json_object, code-fence stripping and
strict-then-lenient JSON parse. Unlike the app it never raises for
transport/JSON problems: every outcome is captured in a CallResult so the
benchmark can measure availability.
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import requests

API_URL = "https://openrouter.ai/api/v1/chat/completions"
MODELS_URL = "https://openrouter.ai/api/v1/models"

CONNECT_TIMEOUT = 15
READ_TIMEOUT = 60

# error_kind values
OK = "none"
RATE_LIMITED = "rate_limited"
HTTP_5XX = "http_5xx"
HTTP_4XX = "http_4xx"
TIMEOUT = "timeout"
CONN_ERROR = "conn_error"
EMPTY_CHOICES = "empty_choices"
INVALID_JSON = "invalid_json"


@dataclass
class CallResult:
    model: str
    status: int = 0            # HTTP status (0 = never reached server)
    latency_ms: float = 0.0
    error_kind: str = OK
    retry_after: Optional[int] = None
    retries: int = 0           # how many 429 retries were spent before this result
    raw_content: Optional[str] = None   # model message content (post-fence-strip)
    parsed: Optional[Dict[str, Any]] = None  # parsed JSON envelope, or None
    error_detail: Optional[str] = None

    @property
    def transport_ok(self) -> bool:
        """The server returned a model completion (HTTP 200)."""
        return self.status == 200

    @property
    def json_ok(self) -> bool:
        return self.parsed is not None


def strip_code_fences(s: Optional[str]) -> Optional[str]:
    """Mirror AgentClient.stripCodeFences."""
    if s is None:
        return None
    s = s.strip()
    if s.startswith("```"):
        nl = s.find("\n")
        if nl != -1:
            s = s[nl + 1:]
        if s.endswith("```"):
            s = s[:-3]
        s = s.strip()
    return s


_TRAILING_COMMA = re.compile(r",(\s*[}\]])")


def parse_envelope(content: str) -> Optional[Dict[str, Any]]:
    """Strict JSON parse, then a lenient fallback tolerating trailing commas,
    approximating the JsonParser fallback in AgentClient."""
    try:
        obj = json.loads(content)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        pass
    try:
        cleaned = _TRAILING_COMMA.sub(r"\1", content)
        obj = json.loads(cleaned)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        return None


def chat(api_key: str, model: str, system_prompt: str,
         history: Optional[List[Dict[str, str]]] = None,
         user_message: Optional[str] = None,
         session: Optional[requests.Session] = None) -> CallResult:
    messages: List[Dict[str, str]] = [{"role": "system", "content": system_prompt}]
    if history:
        messages.extend(history)
    if user_message is not None:
        messages.append({"role": "user", "content": user_message})

    body = {
        "model": model,
        "messages": messages,
        "response_format": {"type": "json_object"},
    }
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    }

    res = CallResult(model=model)
    http = session or requests
    start = time.monotonic()
    try:
        resp = http.post(API_URL, headers=headers, json=body,
                         timeout=(CONNECT_TIMEOUT, READ_TIMEOUT))
    except requests.exceptions.Timeout:
        res.error_kind = TIMEOUT
        res.latency_ms = (time.monotonic() - start) * 1000
        return res
    except requests.exceptions.RequestException as e:
        res.error_kind = CONN_ERROR
        res.error_detail = str(e)[:300]
        res.latency_ms = (time.monotonic() - start) * 1000
        return res

    res.latency_ms = (time.monotonic() - start) * 1000
    res.status = resp.status_code

    if resp.status_code == 429:
        res.error_kind = RATE_LIMITED
        ra = resp.headers.get("Retry-After")
        if ra:
            try:
                res.retry_after = int(ra.strip())
            except ValueError:
                res.retry_after = 30
        else:
            res.retry_after = 30
        return res

    if resp.status_code != 200:
        res.error_kind = HTTP_5XX if resp.status_code >= 500 else HTTP_4XX
        res.error_detail = resp.text[:300]
        return res

    try:
        payload = resp.json()
    except ValueError:
        res.error_kind = INVALID_JSON
        res.error_detail = resp.text[:300]
        return res

    choices = payload.get("choices") or []
    if not choices:
        res.error_kind = EMPTY_CHOICES
        return res

    content = strip_code_fences((choices[0].get("message") or {}).get("content"))
    res.raw_content = content
    if not content:
        res.error_kind = EMPTY_CHOICES
        return res

    parsed = parse_envelope(content)
    if parsed is None:
        res.error_kind = INVALID_JSON
        return res
    if parsed.get("actions") is None:
        parsed["actions"] = []
    res.parsed = parsed
    return res


def discover_free_models(api_key: str,
                         session: Optional[requests.Session] = None) -> List[str]:
    """Return ids of models whose prompt+completion price is zero or whose id
    ends with ':free'."""
    http = session or requests
    resp = http.get(MODELS_URL, headers={"Authorization": f"Bearer {api_key}"},
                    timeout=(CONNECT_TIMEOUT, READ_TIMEOUT))
    resp.raise_for_status()
    data = resp.json().get("data", [])
    free: List[str] = []
    for m in data:
        mid = m.get("id", "")
        pricing = m.get("pricing", {}) or {}

        def _zero(key: str) -> bool:
            v = pricing.get(key)
            try:
                return float(v) == 0.0
            except (TypeError, ValueError):
                return False

        if mid.endswith(":free") or (_zero("prompt") and _zero("completion")):
            free.append(mid)
    return sorted(set(free))
