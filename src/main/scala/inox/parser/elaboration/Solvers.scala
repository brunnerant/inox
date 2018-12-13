package inox.parser.elaboration

import inox.parser.{ElaborationErrors, IRs}
import scala.util.parsing.input.Position

trait Solvers{ self: Constraints with SimpleTypes with IRs with ElaborationErrors =>

  import SimpleTypes._
  import TypeClasses._
  import Constraints.{HasClass, Exists, Equals, OneOf}

  private def isCompatible(tpe: SimpleTypes.Type, value: SimpleTypes.Type): Boolean = (tpe, value) match {
    case (_: Unknown, _) => true
    case (_, _: Unknown) => true
    case (UnitType(), UnitType()) => true
    case (IntegerType(), IntegerType()) => true
    case (BitVectorType(signed1, size1), BitVectorType(signed2, size2)) if signed1 == signed2 && size1 == size2 => true
    case (BooleanType(), BooleanType()) => true
    case (StringType(), StringType()) => true
    case (CharType(), CharType()) => true
    case (RealType(), RealType()) => true
    case (FunctionType(fs1, t1), FunctionType(fs2, t2)) if fs1.size == fs2.size =>
      fs1.zip(fs2).forall(a => isCompatible(a._1, a._2)) && isCompatible(t1, t2)

    case (TupleType(es1), TupleType(es2)) if es1.size == es2.size =>
      es1.zip(es2).forall(a =>isCompatible(a._1, a._2))
    case (MapType(f1, t1), MapType(f2, t2)) => isCompatible(f1, f2) && isCompatible(t1, t2)
    case (SetType(e1), SetType(e2)) => isCompatible(e1, e2)
    case (BagType(e1), BagType(e2)) => isCompatible(e1, e2)
    case (ADTType(i1, as1), ADTType(i2, as2)) if i1 == i2 && as1.size == as2.size =>
      as1.zip(as2).forall(a => isCompatible(a._1, a._2))
    case (TypeParameter(i1), TypeParameter(i2)) if i1 == i2 => true
    case _ => false
  }


  def solve(constraints: Seq[Constraint]): Either[ErrorMessage, Unifier] = {

    case class UnificationError(message: Seq[Position] => ErrorMessage, positions: Seq[Position]) extends Exception(message(positions))

    var unknowns: Set[Unknown] = Set()
    var remaining: Seq[Constraint] = constraints
    var typeClasses: Map[Unknown, TypeClass] = Map()
    var unifier: Unifier = Unifier.empty
    var typeOptionsMap: Map[Unknown, Seq[SimpleTypes.Type]] = Map()

    def unify(unknown: Unknown, value: Type) {

      typeClasses.get(unknown).foreach { tc =>
        remaining :+= HasClass(value, tc)
      }
      typeClasses -= unknown

      typeOptionsMap.get(unknown).foreach { options =>
        typeOptionsMap -= unknown
        expandOneOf(value, options)
      }


      val singleton = Unifier(unknown -> value)

      typeClasses = singleton(typeClasses)
      remaining = singleton(remaining)
      typeOptionsMap = singleton(typeOptionsMap)

      unknowns -= unknown

      unifier += (unknown -> value)
    }

    /**
      * Collects possible optional mappings only on types deemed compatible before.
      * @param tpe
      * @param typeOptions
      * @return
      */
    def expandOneOf(tpe: SimpleTypes.Type, typeOptions: Seq[SimpleTypes.Type]) = {
      var mappings: Map[Unknown, Seq[SimpleTypes.Type]] = Map()

      def collectOptions(tpe: Type, typeOption: Type): Unit = (tpe, typeOption) match {
        case (u1: Unknown, u2: Unknown) =>
          mappings += (u1 -> (mappings.getOrElse(u1, Seq.empty) :+ u2))
          mappings += (u2 -> (mappings.getOrElse(u2, Seq.empty) :+ u1))
        case (u: Unknown, _) => mappings += (u -> (mappings.getOrElse(u, Seq.empty) :+ typeOption))
        case (_, u: Unknown) => mappings += (u -> (mappings.getOrElse(u, Seq.empty) :+ tpe))
        case (UnitType(), UnitType()) => ()
        case (IntegerType(), IntegerType()) => ()
        case (StringType(), StringType()) => ()
        case (RealType(), RealType()) => ()
        case (BooleanType(), BooleanType()) => ()
        case (BitVectorType(signed1, size1), BitVectorType(signed2, size2)) if signed1 == signed2 && size1 == size2 => ()
        case (CharType(), CharType()) => ()
        case (FunctionType(fs1, t1), FunctionType(fs2, t2)) if fs1.size == fs2.size =>
          fs1.zip(fs2).foreach(pair => collectOptions(pair._1, pair._2))
          collectOptions(t1, t2)
        case (TupleType(es1), TupleType(es2)) =>
          es1.zip(es2).foreach(pair => collectOptions(pair._1, pair._2))
        case (MapType(f1, t1), MapType(f2, t2)) =>
          collectOptions(f1, f2)
          collectOptions(t1, t2)
        case (SetType(t1), SetType(t2)) =>
          collectOptions(t1, t2)
        case (BagType(t1), BagType(t2)) =>
          collectOptions(t1, t2)
        case (ADTType(i1, as1), ADTType(i2, as2)) if i1 == i2 && as1.size == as2.size =>
          as1.zip(as2).foreach { pair => collectOptions(pair._1, pair._2) }
        case (TypeParameter(i1), TypeParameter(i2)) if i1 == i2 => ()
        case _ => throw new Exception("Should never reach this pattern")
      }

      val possibleOptions = typeOptions.filter(isCompatible(tpe, _))
      if (possibleOptions.isEmpty)
        throw new Exception("One of has no possible type options")
      else if (possibleOptions.size == 1)
        remaining :+= Constraint.equal(tpe, possibleOptions.head)
      else {
        possibleOptions.foreach(collectOptions(tpe, _))
        val merged = mappings.toSeq ++ typeOptionsMap
        val grouped: Map[Unknown, Seq[(Unknown, Seq[Type])]] = merged.groupBy(_._1)
        val cleaned = grouped.mapValues(_.map(_._2).flatten.toSet.toList)
        val (options, equalities) = cleaned.partition(a => a._2.size > 1)
        typeOptionsMap = options
        equalities.foreach(pair => remaining :+= Equals(pair._1, pair._2.head))
        typeOptionsMap = cleaned
      }

    }

    def handle(constraint: Constraint): Unit = constraint match {
      case Exists(tpe) => tpe match {
        case u: Unknown => unknowns += u
        case _ => remaining ++= tpe.unknowns.map(Exists(_))
      }
      case Equals(tpe1, tpe2) => (tpe1, tpe2) match {
        case (u1: Unknown, u2: Unknown) => if (u1 != u2) unify(u1, u2) else unknowns += u1
        case (u1: Unknown, _) => if (!tpe2.contains(u1)) unify(u1, tpe2) else throw UnificationError(unificationImpossible(tpe1, tpe2), Seq(tpe1.pos, tpe2.pos))
        case (_, u2: Unknown) => if (!tpe1.contains(u2)) unify(u2, tpe1) else throw UnificationError(unificationImpossible(tpe1, tpe2), Seq(tpe1.pos, tpe2.pos))
        case (UnitType(), UnitType()) => ()
        case (IntegerType(), IntegerType()) => ()
        case (BitVectorType(signed1, size1), BitVectorType(signed2, size2)) if signed1 == signed2 && size1 == size2 => ()
        case (BooleanType(), BooleanType()) => ()
        case (StringType(), StringType()) => ()
        case (CharType(), CharType()) => ()
        case (RealType(), RealType()) => ()
        case (FunctionType(fs1, t1), FunctionType(fs2, t2)) if fs1.size == fs2.size => {
          remaining ++= fs1.zip(fs2).map { case (f1, f2) => Equals(f1, f2) }
          remaining :+= Equals(t1, t2)
        }
        case (TupleType(es1), TupleType(es2)) if es1.size == es2.size =>
          remaining ++= es1.zip(es2).map { case (e1, e2) => Equals(e1, e2) }
        case (MapType(f1, t1), MapType(f2, t2)) => {
          remaining :+= Equals(f1, f2)
          remaining :+= Equals(t1, t2)
        }
        case (SetType(e1), SetType(e2)) => remaining :+= Equals(e1, e2)
        case (BagType(e1), BagType(e2)) => remaining :+= Equals(e1, e2)
        case (ADTType(i1, as1), ADTType(i2, as2)) if i1 == i2 && as1.size == as2.size =>
          remaining ++= as1.zip(as2).map { case (a1, a2) => Equals(a1, a2) }
        case (TypeParameter(i1), TypeParameter(i2)) if i1 == i2 => ()
        case _ => throw UnificationError(unificationImpossible(tpe1, tpe2), Seq(tpe1.pos, tpe2.pos))
      }
      case HasClass(tpe, tc) => tpe match {
        case u: Unknown => {
          unknowns += u
          typeClasses.get(u) match {
            case None => typeClasses += (u -> tc)
            case Some(tc2) => tc.combine(tc2)(tpe) match {
              case None => throw UnificationError(incompatibleTypeClasses(tc, tc2), Seq(tpe.pos))
              case Some(cs) => {
                typeClasses -= u
                remaining ++= cs
              }
            }
          }
        }
        case _ => tc.accepts(tpe) match {
          case None => throw UnificationError(notMemberOfTypeClasses(tpe, tc), Seq(tpe.pos))
          case Some(cs) => remaining ++= cs
        }
      }

      case OneOf(tpe, typeOptions) => tpe match {
        case u: Unknown =>
          typeOptionsMap += (u -> typeOptions)
        case _ =>
          expandOneOf(tpe, typeOptions)
      }
    }



    try {
      while (unknowns.nonEmpty || remaining.nonEmpty) {

        while (remaining.nonEmpty) {
          val constraint = remaining.head
          remaining = remaining.tail

          handle(constraint)
        }

        if (unknowns.nonEmpty) {
          val defaults = typeClasses.collect {
            case (u, Integral) => u -> IntegerType()
            case (u, Numeric) => u -> IntegerType()
          }.toSeq

          defaults.foreach {
            case (u, t) => remaining :+= Equals(u, t)
          }

          if (defaults.isEmpty) {
            throw UnificationError(ambiguousTypes, unknowns.toSeq.map(_.pos))
          }
        }
      }

      Right(unifier)
    }
    catch {
      case UnificationError(error, positions) => Left(error(positions))
    }
  }
}
