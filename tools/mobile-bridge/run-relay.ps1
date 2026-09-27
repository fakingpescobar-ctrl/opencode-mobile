# Запуск автопилота-наблюдателя (relay) в отдельном окне.
# Триггер-договор с мобильным агентом: запросы ПК-модели пишутся в pc-inbox.jsonl.
Set-Location 'C:\Projects\ocmobile\tools\mobile-bridge'
node bridge.mjs relay --session ses_fa3900853ffev55wl2Q79cNUrU --report relay.jsonl --interval 20 "--max-replies" 5 --guard