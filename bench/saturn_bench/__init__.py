"""Saturn model benchmark.

A standalone tool that scores free OpenRouter models on Saturn's task-action
protocol (intelligence) and on their real-world reliability (availability).

The prompt builders in :mod:`prompts` and the action executor in :mod:`executor`
are hand-ports of the app's Java source. See each module header for the source
range they mirror and the obligation to keep them in sync.
"""
