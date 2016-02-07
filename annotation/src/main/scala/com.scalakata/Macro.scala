package com.scalakata

import language.experimental.macros

object KataMacro {
  def instrument(c: reflect.macros.whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Instrumented] = {
    import c.universe._

    def instrumentOne(tree: Tree, instrumentation: TermName) = {
      implicit def liftq = Liftable[c.universe.Position] { p ⇒
        q"_root_.com.scalakata.RangePosition(${p.start}, ${p.start}, ${p.end})"
      }

      def w(aTree: Tree) = {
        val t = TermName(c.freshName)
        if(aTree.pos == NoPosition) aTree
        else {
          q"""{
            val $t = $aTree
            ${instrumentation}(${aTree.pos}) = render($t)
            $t
          }"""
        }
      }

      // see http://docs.scala-lang.org/overviews/quasiquotes/syntax-summary.html
      tree match {
        case q"$_ match { case ..$_ }" ⇒ w(tree)
        case q"$expr[..$tpts]"         ⇒ w(tree)   // implicitly[Ordering[Int]]
        case q"$expr: $tpt"            ⇒ w(tree)   // a: Int
        case q"for (..$enums) $expr"   ⇒ tree
        case _: Apply                  ⇒ w(tree)   // f(1)
        case _: Select                 ⇒ w(tree)   // p.x
        case _: Ident                  ⇒ w(tree)   // p
        case _: Block                  ⇒ w(tree)   // {a; b}
        case _: Try                    ⇒ w(tree)   // try ...
        case v                         ⇒ v
      }
    }

    c.Expr[Instrumented]{
      annottees.map(_.tree).toList match { case q"class $name { ..$body }" :: Nil ⇒
        val instrumentation = TermName(c.freshName)

        val offset = 
          c.enclosingPosition.end + (
            " " +
            s"""|class $name {
                |""".stripMargin
          ).length

        q"""
        class $name extends Instrumented {
          private val $instrumentation = scala.collection.mutable.Map[_root_.com.scalakata.RangePosition, Render]()
          def offset$$ = $offset
          def instrumentation$$: _root_.com.scalakata.Instrumentation = ${instrumentation}.toList.sorted
          ..${body.map(t ⇒ instrumentOne(t, instrumentation))}
        }
        """
      }
    }
  }
}

trait Instrumented {
  def instrumentation$: Instrumentation
  def offset$: Int
}

class instrument extends annotation.StaticAnnotation {
  def macroTransform(annottees: Any*): Instrumented = macro KataMacro.instrument
}