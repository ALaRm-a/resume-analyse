请基于以下信息生成一个追问问题。

【主问题】{category}：{question}
【候选人回答】
{answer}
【回答弱点】
{weakness}
【候选人简历（参考）】
{resume}

请输出一个 JSON 对象（不要包含 Markdown 代码块标签、不要输出任何解释文字），字段如下：
- question: 字符串，追问问题（必须是完整的问句）
- category: 字符串，追问所属类别（沿用主问题类别或更细化）
