// Script de verificação manual ponta a ponta do fluxo de Entrada de Dados +
// Conferência do Fornecedor + Comparativo/Mapa de Compra, via browser real
// (Playwright). Não é um teste automatizado de CI — é a mesma checagem que rodei
// manualmente ao final do refactor de fluxo sequencial/Conferência (ver
// /home/nicolas/.claude/plans/prompt-refatora-o-golden-goblet.md).
//
// Pré-requisitos antes de rodar:
//   1. Backend rodando (porta 8080) contra um Postgres com as migrations aplicadas.
//   2. Frontend rodando (`npm run dev`, porta 3000 por padrão).
//   3. Um usuário de teste já cadastrado no tenant que você quer usar — informe
//      as credenciais via env vars (ver abaixo). Este script NÃO cria tenant/
//      usuário sozinho (isso depende do backend/banco que você está rodando).
//
// Variáveis de ambiente (todas opcionais, com default):
//   BASE_URL   - URL do frontend (default http://localhost:3000)
//   E2E_EMAIL  - e-mail do usuário de teste (default verify@prx.com)
//   E2E_SENHA  - senha do usuário de teste (default teste123)
//   HEADLESS   - "false" para ver o browser rodando (default true)
//
// Uso:
//   npm install --save-dev playwright   (uma vez só)
//   npx playwright install chromium     (uma vez só, baixa o browser)
//   node scripts/e2e-verify.js
//
// Screenshots de cada etapa vão para scripts/e2e-shots/ (gitignored).

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const BASE = process.env.BASE_URL || 'http://localhost:3000';
const EMAIL = process.env.E2E_EMAIL || 'verify@prx.com';
const SENHA = process.env.E2E_SENHA || 'teste123';
const HEADLESS = process.env.HEADLESS !== 'false';

const shots = path.join(__dirname, 'e2e-shots');
fs.mkdirSync(shots, { recursive: true });

const consoleErrors = [];

(async () => {
  const browser = await chromium.launch({ headless: HEADLESS });
  const page = await browser.newPage();
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(`[console] ${msg.text()}`);
  });
  page.on('pageerror', (err) => consoleErrors.push(`[pageerror] ${err.message}`));

  function log(step) { console.log('STEP:', step); }

  // 1. Login
  log('login');
  await page.goto(`${BASE}/login`);
  await page.fill('#email', EMAIL);
  await page.fill('#senha', SENHA);
  await page.click('button[type=submit]');
  await page.waitForURL(`${BASE}/`, { timeout: 10000 });
  await page.screenshot({ path: `${shots}/01-dashboard.png` });

  // 2. Criar cotação
  log('criar cotacao');
  await page.fill('input[placeholder^="Título da nova cotação"]', `Cotação Verify ${Date.now()}`);
  await page.click('button:has-text("Nova cotação")');
  await page.waitForURL(/\/cotacoes\/.+\/entrada/, { timeout: 10000 });
  const cotacaoUrl = page.url();
  console.log('cotacaoUrl:', cotacaoUrl);
  await page.screenshot({ path: `${shots}/02-entrada-vazia.png` });

  // 3. Colar lista de produtos
  log('colar lista');
  await page.fill('textarea[placeholder*="sazon legumes"]', '2 un Sazon Legumes 60g\n1 kg Arroz Tipo 1 5kg');
  await page.click('button:has-text("Adicionar itens")');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${shots}/03-lista-adicionada.png` });

  // 4. Adicionar fornecedor via autocomplete -> cadastrar novo
  // Com a cotação sem nenhum fornecedor ainda, o componente já abre direto no modo
  // "adicionar" (autocomplete visível) — não precisa clicar em "+ Adicionar Fornecedor"
  // antes (esse botão fica desabilitado de propósito enquanto já está em modo adicionar).
  log('adicionar fornecedor');
  const nomeFornecedor = `Distribuidora Teste ${Date.now()}`;
  await page.fill('input[placeholder="Buscar fornecedor..."]', nomeFornecedor);
  await page.click('button:has-text("+ Cadastrar novo fornecedor")');
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${shots}/04-modal-novo-fornecedor.png` });
  await page.click('button:has-text("Salvar")');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${shots}/05-fornecedor-adicionado.png` });

  // 5. Colar resposta do fornecedor (1 item OK, 1 item Revisar via "consultar")
  log('colar resposta');
  await page.fill('textarea[placeholder*="Sazon Legumes"]', 'Sazon Legumes 60g - R$ 4,89\nArroz Tipo 1 5kg - consultar');
  await page.click('button:has-text("Processar Cotação")');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${shots}/06-conferencia-modal.png`, fullPage: true });

  const modalTitulo = await page.locator('text=Conferência do Fornecedor').count();
  if (modalTitulo === 0) throw new Error('Modal de Conferência não abriu após Processar Cotação');

  const contadoresTexto = await page.locator('text=Total').first().locator('xpath=../..').innerText().catch(() => 'n/a');
  console.log('Contadores (bruto):', contadoresTexto);

  // 6. Resolver item em Revisar via edição manual (a linha "consultar" não tem
  // preço extraível do texto, então o candidato correspondente fica com radio
  // desabilitado — correto, "aceitar sugestão" sem preço falharia ao confirmar).
  log('resolver item revisar via edicao manual');
  await page.click('button:has-text("Editar manualmente")');
  await page.waitForTimeout(300);
  await page.locator('input[placeholder="Descrição do produto"]').first().fill('Arroz Tipo 1 5kg');
  await page.locator('input[placeholder="Preço"]').first().fill('22,50');
  await page.click('button:has-text("Salvar")');
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${shots}/07-apos-resolver.png`, fullPage: true });

  // 7. Confirmar e Processar
  log('confirmar e processar');
  const btnConfirmar = page.locator('button:has-text("Confirmar e Processar")');
  const desabilitado = await btnConfirmar.isDisabled();
  console.log('botao Confirmar desabilitado?', desabilitado);
  if (desabilitado) {
    await page.screenshot({ path: `${shots}/07b-confirmar-desabilitado.png`, fullPage: true });
    throw new Error('Botão Confirmar e Processar continua desabilitado após resolver o item Revisar');
  }
  await btnConfirmar.click();
  await page.waitForURL(/\/comparativo/, { timeout: 10000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${shots}/08-comparativo.png`, fullPage: true });

  const aguardandoNoComparativo = await page.locator('text=Aguardando cotação').count();
  console.log('Comparativo mostra "Aguardando cotação"?', aguardandoNoComparativo > 0);

  // 8. Volta pra Entrada de Dados, confere gate do "+ Adicionar Fornecedor"
  log('voltar pra entrada, checar gate');
  await page.goto(cotacaoUrl);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${shots}/09-entrada-pos-confirmacao.png`, fullPage: true });
  const btnAdicionar = page.locator('button:has-text("+ Adicionar Fornecedor")');
  const gateHabilitado = !(await btnAdicionar.isDisabled());
  console.log('"+ Adicionar Fornecedor" habilitado após confirmar?', gateHabilitado);

  // 9. Mapa de Compra
  log('mapa de compra');
  await page.goto(cotacaoUrl.replace('/entrada', '/mapa'));
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${shots}/10-mapa.png`, fullPage: true });
  const aguardandoNoMapa = await page.locator('text=Aguardando cotação').count();
  console.log('Mapa mostra "Aguardando cotação"?', aguardandoNoMapa > 0);

  await browser.close();

  console.log('--- CONSOLE ERRORS CAPTURADOS ---');
  if (consoleErrors.length === 0) console.log('nenhum');
  else consoleErrors.forEach((e) => console.log(e));

  console.log('--- FIM (screenshots em', shots, ') ---');
})().catch((err) => {
  console.error('FALHA:', err);
  process.exit(1);
});
