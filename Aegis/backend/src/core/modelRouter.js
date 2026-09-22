import { taskClassifier } from "./taskClassifier.js";

export class ModelRouter {
  route(prompt, availableAdapters) {
    const cls = taskClassifier.classify(prompt);
    
    let adapter = cls.recommendedAdapter;
    if (!availableAdapters.has(adapter)) {
      adapter = "antigravity"; // fallback
    }
    
    return {
      adapterId: adapter,
      model: cls.complexity === "high" ? "gemini-3.1-pro" : "gemini-3.8-flash-high",
      classification: cls
    };
  }
}
export const modelRouter = new ModelRouter();
