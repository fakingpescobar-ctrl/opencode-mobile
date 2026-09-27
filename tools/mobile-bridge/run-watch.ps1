# Фоновый наблюдатель (watch): дублирует все события в watch.log (читабельно)
# и watch.jsonl (сырьё для diff-анализа ПК-агентом).
Set-Location 'C:\Projects\ocmobile\tools\mobile-bridge'
node bridge.mjs watch --log watch.log --jsonl watch.jsonl --guard