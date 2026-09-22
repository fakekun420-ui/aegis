import { MoneyPrinterAdapter } from "../plugins/content/moneyPrinterAdapter.js";

const printer = new MoneyPrinterAdapter();

export function handleContentRoutes(req, res, pathname, jsonHelper) {
  if (pathname === "/api/content/status" && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, printerAvailable: printer.isAvailable() });
  }
  return false;
}
