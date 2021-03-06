package molt.syntax.cfg.parsable

import molt.syntax._
import molt.tokenize._
import molt.syntax.cfg._
import molt.syntax.cnf.SmartParseParameters
import molt.syntax.cnf.BasicSmartParse
import molt.syntax.cnf.CNFAST

/*
 * CFGParsable contains most of the functionality and framework for any object
 * that we want to be able to parse from a string, so I make most of the notes
 * here.
 */
sealed trait CFGParsable[+A] {
  // ----- Must implement -----

  // This mapping gives us all of the productions for this particular
  // nonterminal, and paired with them are methods to construct one
  // of this nonterminal-associated `A` from its constituent parts.
  val synchronousProductions: Map[List[CFGParsable[_]], (List[AST[CFGParsable[_]]] => Option[A])]

  val tag: ASTTag[CFGParsable[_]]

  def fromAST(ast: AST[CFGParsable[_]]): Option[A]

  // ----- May be overridden -----

  // These are the strings that will be regarded as individual words (beyond the
  // normal splitting behavior of the tokenizer). Terminals use these.
  // Non "terminal" lexical categories should not add to this. Tokens cannot be
  // ambiguous; i.e., they cannot overlap with each other so as to lead to
  // multiple possible tokenizations.
  // TODO come up with a better solution than JUST individual token symbols
  val tokens: Set[String] = Set.empty[String]

  val schedulingParams: SmartParseParameters[CNFAST[CNFConversionTag[CFGParsable[_]]]] =
    new BasicSmartParse[CNFConversionTag[CFGParsable[_]]]

  // ----- Cannot be overridden -----

  // List of all of the Parsables that are components of this one (used in productions)
  final lazy val children: Set[CFGParsable[_]] = {
    childrenWithout(Set(this))
  }
  private def childrenWithout(prohib: Set[CFGParsable[_]]): Set[CFGParsable[_]] = {
    val topLayer = synchronousProductions.keySet.flatten -- prohib
    val below = topLayer.flatMap(_.childrenWithout(topLayer ++ prohib))
    topLayer ++ below
  }

  // all the lexical categories required to parse this Parsable
  final lazy val lexicalCategories: Set[LexicalCategory[CFGParsable[_]]] = (children + this) collect {
    case (c: CFGParsableLexicalCategory) => c
  }

  // automatically determine the productions to give the grammar from the
  // synchronous productions of the Parsable and its children
  final lazy val processedSynchronousProductions: Map[CFGProduction[CFGParsable[_]], (List[AST[CFGParsable[_]]] => Option[A])] =
    synchronousProductions.map {
      case (k, v) => (CFGProduction[CFGParsable[_]](this, k.map(_.tag)), v)
    }

  final lazy val productions: Set[CFGProduction[CFGParsable[_]]] = {
    processedSynchronousProductions.keySet ++
    children.flatMap(_.processedSynchronousProductions.keySet)
  }

  final lazy val allTokens: Set[String] =
    tokens ++ children.flatMap(_.tokens)

  // TODO might want something more general than always the basic tokenizer
  final lazy val tokenizer: Tokenizer = new MaximalMunchTokenizer(allTokens)

  // the grammar just requires the productions, lexical categories, and start symbol
  final lazy val grammar: ContextFreeGrammar[CFGParsable[_]] =
    new ContextFreeGrammar[CFGParsable[_]](productions, lexicalCategories, Set(this))

  final lazy val parser: SchedulingCFGParser[CFGParsable[_]] =
    new SchedulingCFGParser(grammar, schedulingParams)
}

/*
 * ComplexParsable objects rely on
 * their productions and determine everything from
 * there. The bulk of objects will implement this one.
 */
trait ComplexCFGParsable[+A] extends CFGParsable[A] {
  final override val tag = ASTNormalTag(this)
  final def fromAST(ast: AST[CFGParsable[_]]): Option[A] = ast match {
    case ASTNonterminal(head, children) => for {
      p <- Some(CFGProduction(head, children.map(_.tag)))
      func <- processedSynchronousProductions.get(p)
      item <- func(children)
    } yield item
    case _ => None
  }
}

trait CFGParsableLexicalCategory extends LexicalCategory[CFGParsable[_]] with CFGParsable[String] {
  final override val symbol = this
  final override val tag = ASTNormalTag(this)
  final override val synchronousProductions =
    Map[List[CFGParsable[_]], (List[AST[CFGParsable[_]]] => Option[String])]()
  final override def fromAST(ast: AST[CFGParsable[_]]): Option[String] = ast match {
    case ASTTerminal(`symbol`, str) if member(str) => Some(str)
    case _ => None
  }
}

case object CFGEmptyCategory extends CFGParsable[Nothing] {
  final override val tag = ASTEmptyTag
  final override val synchronousProductions =
    Map[List[CFGParsable[_]], (List[AST[CFGParsable[_]]] => Option[Nothing])]()
  final override def fromAST(ast: AST[CFGParsable[_]]): Option[Nothing] = None
}
