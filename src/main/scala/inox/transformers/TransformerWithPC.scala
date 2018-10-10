/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package transformers

/** A [[Transformer]] that uses the path condition _before transformation_ as environment */
trait TransformerWithPC extends Transformer {
  type Env <: s.PathLike[Env]

  override def transform(e: s.Expr, env: Env): t.Expr = e match {
    case s.Let(i, v, b) =>
      t.Let(transform(i, env), transform(v, env), transform(b, env withBinding (i -> v))).copiedFrom(e)

    case s.Lambda(params, b) =>
      val (sparams, senv) = params.foldLeft((Seq[t.ValDef](), env)) {
        case ((sparams, env), vd) => (sparams :+ transform(vd, env), env withBound vd)
      }
      t.Lambda(sparams, transform(b, senv)).copiedFrom(e)

    case s.Forall(params, b) =>
      val (sparams, senv) = params.foldLeft((Seq[t.ValDef](), env)) {
        case ((sparams, env), vd) => (sparams :+ transform(vd, env), env withBound vd)
      }
      t.Forall(sparams, transform(b, senv)).copiedFrom(e)

    case s.Choose(res, p) =>
      t.Choose(transform(res, env), transform(p, env withBound res)).copiedFrom(e)

    case s.Assume(pred, body) =>
      t.Assume(transform(pred, env), transform(body, env withCond pred)).copiedFrom(e)

    case s.IfExpr(cond, thenn, elze) =>
      t.IfExpr(
        transform(cond, env),
        transform(thenn, env withCond cond),
        transform(elze, env withCond s.Not(cond).copiedFrom(cond))
      ).copiedFrom(e)

    case s.And(es) =>
      var soFar = env
      t.andJoin(for(e <- es) yield {
        val se = transform(e, soFar)
        soFar = soFar withCond e
        se
      }).copiedFrom(e)

    case s.Or(es) =>
      var soFar = env
      t.orJoin(for(e <- es) yield {
        val se = transform(e, soFar)
        soFar = soFar withCond s.Not(e).copiedFrom(e)
        se
      }).copiedFrom(e)

    case s.Implies(lhs, rhs) =>
      t.Implies(transform(lhs, env), transform(rhs, env withCond lhs)).copiedFrom(e)

    case _ => super.transform(e, env)
  }
}

