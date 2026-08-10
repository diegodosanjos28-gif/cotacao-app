-- lista_revisada sinaliza se o usuário já revisou a lista recebida via WhatsApp
-- antes de seguir para a Conferência (tela "Ajuste de Lista", Fase 3). Cotações
-- web nunca passam por essa tela — nascem TRUE e nunca mudam.
ALTER TABLE cotacao ADD COLUMN lista_revisada BOOLEAN NOT NULL DEFAULT TRUE;
