export class TaskClassifier {
  classify(prompt, context) {
    const text = (prompt || "").toLowerCase();
    
    if (text.includes("arquitectura") || text.includes("diseña")) {
      return { type: "architecture", complexity: "high", recommendedAdapter: "antigravity" };
    }
    if (text.includes("bug") || text.includes("fix") || text.includes("corrige")) {
      return { type: "coding", complexity: "medium", recommendedAdapter: "claudecode" };
    }
    
    return { type: "quick", complexity: "low", recommendedAdapter: "antigravity" };
  }
}
export const taskClassifier = new TaskClassifier();
