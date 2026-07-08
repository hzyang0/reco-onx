// 这是后续要迁移成 Java 算子的“图上 Groovy 脚本”示例。
// 第一版项目不会真正执行它，只用它帮助理解 Groovy 在 DAG 图上的角色。

if (abParams["recall_exp"] == "B" && userFeature.newUser) {
    context.recallLimit = 500
} else {
    context.recallLimit = 200
}
