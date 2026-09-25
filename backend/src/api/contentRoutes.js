import { MoneyPrinterAdapter } from "../plugins/content/moneyPrinterAdapter.js";

const printer = new MoneyPrinterAdapter();

export function handleContentRoutes(req, res, pathname, jsonHelper) {
  if (pathname === "/api/content/status" && req.method === "GET") {
    // A-3: envuelto — éxito estándar {ok:true, data:...}
    return jsonHelper(res, 200, { ok: true, data: { printerAvailable: printer.isAvailable() } });
  }
  return false;
}
