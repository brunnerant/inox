/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers

import evaluators._
import transformers._

package object theories {

  case class TheoryException(msg: String)
    extends Unsupported(s"Theory encoding failed: $msg")

  def Z3(p: Program): ProgramTransformer {
    val sourceProgram: p.type
    val targetProgram: Program { val trees: p.trees.type }
  } = ASCIIStringEncoder(p)

  def CVC4(enc: ProgramTransformer)
          (ev: DeterministicEvaluator { val program: enc.sourceProgram.type }): ProgramTransformer {
    val sourceProgram: enc.targetProgram.type
    val targetProgram: Program { val trees: enc.targetProgram.trees.type }
  } = {
    val stringEncoder = ASCIIStringEncoder(enc.targetProgram)
    val encAndString = enc andThen stringEncoder
    val bagEncoder = BagEncoder(encAndString)(ev)
    stringEncoder andThen bagEncoder
  }

  def Princess(enc: ProgramTransformer)
              (ev: DeterministicEvaluator { val program: enc.sourceProgram.type }): ProgramTransformer {
    val sourceProgram: enc.targetProgram.type
    val targetProgram: Program { val trees: enc.targetProgram.trees.type }
  } = {
    val stringEncoder = StringEncoder(enc.targetProgram)

    val encAndString = enc andThen stringEncoder
    val bagEncoder = BagEncoder(encAndString)(ev)

    val setEncoder = SetEncoder(bagEncoder.targetProgram)

    val realEncoder = RealEncoder(setEncoder.targetProgram)

    // @nv: Required due to limitations in scalac existential types
    val e1 = stringEncoder andThen bagEncoder
    val e2 = e1 andThen setEncoder
    e2 andThen realEncoder
  }

  object ReverseEvaluator {
    def apply(enc: ProgramTransformer)
             (ev: DeterministicEvaluator { val program: enc.sourceProgram.type }):
             DeterministicEvaluator { val program: enc.targetProgram.type } = new {
      val program: enc.targetProgram.type = enc.targetProgram
      val context = ev.context
    } with DeterministicEvaluator {
      import program.trees._
      import EvaluationResults._

      def eval(e: Expr, model: program.Model): EvaluationResult = {
        ev.eval(enc.decode(e), model.encode(enc.reverse)) match {
          case Successful(value) => Successful(enc.encode(value))
          case RuntimeError(message) => RuntimeError(message)
          case EvaluatorError(message) => EvaluatorError(message)
        }
      }
    }
  }
}

