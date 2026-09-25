import fs from "node:fs";

export class MoneyPrinterAdapter {
  isAvailable() {
    return fs.existsSync("/sdcard/projects/MoneyPrinterTurbo");
  }
  
  async generateVideo(script, outputDir) {
    if (!this.isAvailable()) throw new Error("MoneyPrinterTurbo not installed");
    return { ok: true, file: "video.mp4" };
  }
}
