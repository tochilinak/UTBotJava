package parser.ast

import com.eclipsesource.v8.V8Object
import parser.TsParserUtils.getAstNodeByKind
import parser.TsParserUtils.getChildren

class DummyNode(
    obj: V8Object,
    override val parent: AstNode?
): AstNode() {

    override val children = obj.getChildren().map { it.getAstNodeByKind(this) }
}