/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package solvers.z3

import utils._

import z3.scala.{Z3Solver => ScalaZ3Solver, _}
import solvers._

case class UnsoundExtractionException(ast: Z3AST, msg: String)
  extends Exception("Can't extract " + ast + " : " + msg)

// This is just to factor out the things that are common in "classes that deal
// with a Z3 instance"
trait AbstractZ3Solver
  extends AbstractSolver
     with ADTManagers {

  import program._
  import program.trees._
  import program.symbols._
  import program.symbols.bestRealType

  import SolverResponses._

  val name = "AbstractZ3"

  ctx.interruptManager.registerForInterrupts(this)

  type Trees = Z3AST
  type Model = Z3Model
  type Cores = Set[Z3AST]

  private[this] var freed = false
  val traceE = new Exception()

  protected def unsound(ast: Z3AST, msg: String): Nothing =
    throw UnsoundExtractionException(ast, msg)

  override def finalize() {
    if (!freed) {
      println("!! Solver "+this.getClass.getName+"["+this.hashCode+"] not freed properly prior to GC:")
      traceE.printStackTrace()
      free()
    }
  }

  protected[z3] var z3 : Z3Context = new Z3Context(
    "MODEL"             -> true,
    "TYPE_CHECK"        -> true,
    "WELL_SORTED_CHECK" -> true
  )

  // @nv: This MUST take place AFTER Z3Context was created!!
  // Well... actually maybe not, but let's just leave it here to be sure
  toggleWarningMessages(true)

  protected var solver : ScalaZ3Solver = null

  override def free(): Unit = {
    freed = true
    if (z3 ne null) {
      z3.delete()
      z3 = null
    }
  }

  override def interrupt(): Unit = {
    if(z3 ne null) {
      z3.interrupt()
    }
  }

  override def recoverInterrupt(): Unit = ()

  def functionDefToDecl(tfd: TypedFunDef): Z3FuncDecl = {
    functions.cachedB(tfd) {
      val sortSeq    = tfd.params.map(vd => typeToSort(vd.getType))
      val returnSort = typeToSort(tfd.returnType)

      z3.mkFreshFuncDecl(tfd.id.uniqueName, sortSeq, returnSort)
    }
  }

  def declareVariable(v: Variable): Z3AST = variables.cachedB(v) {
    symbolToFreshZ3Symbol(v)
  }

  // ADT Manager
  private val adtManager = new ADTManager

  // Bijections between Leon Types/Functions/Ids to Z3 Sorts/Decls/ASTs
  private val functions = new IncrementalBijection[TypedFunDef, Z3FuncDecl]()
  private val lambdas   = new IncrementalBijection[FunctionType, Z3FuncDecl]()
  private val variables = new IncrementalBijection[Variable, Z3AST]()

  private val constructors = new IncrementalBijection[Type, Z3FuncDecl]()
  private val selectors    = new IncrementalBijection[(Type, Int), Z3FuncDecl]()
  private val testers      = new IncrementalBijection[Type, Z3FuncDecl]()

  private val sorts     = new IncrementalMap[Type, Z3Sort]()

  def push(): Unit = {
    adtManager.push()
    functions.push()
    lambdas.push()
    variables.push()

    constructors.push()
    selectors.push()
    testers.push()

    sorts.push()
    if (isInitialized) solver.push()
  }

  def pop(): Unit = {
    adtManager.pop()
    functions.pop()
    lambdas.pop()
    variables.pop()

    constructors.pop()
    selectors.pop()
    testers.pop()

    sorts.pop()
    if (isInitialized) solver.pop()
  }

  def reset(): Unit = {
    throw new CantResetException(this)
  }

  private var isInitialized = false
  protected def initZ3(): Unit = {
    if (!isInitialized) {
      solver = z3.mkSolver()

      functions.clear()
      lambdas.clear()
      variables.clear()
      constructors.clear()
      selectors.clear()
      testers.clear()
      sorts.reset()

      prepareSorts()

      isInitialized = true
    }
  }

  initZ3()

  def declareStructuralSort(t: Type): Z3Sort = {
    adtManager.declareADTs(t, declareDatatypes)
    sorts(bestRealType(t))
  }

  def declareDatatypes(adts: Seq[(Type, DataType)]): Unit = {
    import Z3Context.{ADTSortReference, RecursiveType, RegularSort}

    val indexMap: Map[Type, Int] = adts.map(_._1).zipWithIndex.toMap

    def typeToSortRef(tt: Type): ADTSortReference = {
      val tpe = tt match {
        case ct: ClassType => ct.tcd.root.toType
        case _ => tt
      }

      if (indexMap contains tpe) {
        RecursiveType(indexMap(tpe))
      } else {
        RegularSort(typeToSort(tt))
      }
    }

    // Define stuff
    val defs = for ((_, DataType(sym, cases)) <- adts) yield {(
      sym.uniqueName,
      cases.map(c => c.sym.uniqueName),
      cases.map(c => c.fields.map { case(id, tpe) => (id.uniqueName, typeToSortRef(tpe))})
    )}

    val resultingZ3Info = z3.mkADTSorts(defs)

    for ((z3Inf, (tpe, DataType(sym, cases))) <- resultingZ3Info zip adts) {
      sorts += (tpe -> z3Inf._1)
      assert(cases.size == z3Inf._2.size)

      for ((c, (consFun, testFun)) <- cases zip (z3Inf._2 zip z3Inf._3)) {
        testers += (c.tpe -> testFun)
        constructors += (c.tpe -> consFun)
      }

      for ((c, fieldFuns) <- cases zip z3Inf._4) {
        assert(c.fields.size == fieldFuns.size)

        for ((selFun, index) <- fieldFuns.zipWithIndex) {
          selectors += (c.tpe, index) -> selFun
        }
      }
    }
  }

  // Prepares some of the Z3 sorts, but *not* the tuple sorts; these are created on-demand.
  private def prepareSorts(): Unit = {

    //TODO: mkBitVectorType
    sorts += Int32Type -> z3.mkBVSort(32)
    sorts += CharType -> z3.mkBVSort(32)
    sorts += IntegerType -> z3.mkIntSort
    sorts += RealType -> z3.mkRealSort
    sorts += BooleanType -> z3.mkBoolSort

    testers.clear()
    constructors.clear()
    selectors.clear()
  }

  // assumes prepareSorts has been called....
  protected def typeToSort(oldtt: Type): Z3Sort = bestRealType(oldtt) match {
    case Int32Type | BooleanType | IntegerType | RealType | CharType =>
      sorts(oldtt)

    case tpe @ (_: ClassType  | _: TupleType | _: TypeParameter | UnitType) =>
      sorts.getOrElse(tpe, declareStructuralSort(tpe))

    case tt @ SetType(base) =>
      sorts.cached(tt) {
        declareStructuralSort(tt)
        z3.mkSetSort(typeToSort(base))
      }

    case tt @ BagType(base) =>
      typeToSort(MapType(base, IntegerType))

    case tt @ MapType(from, to) =>
      sorts.cached(tt) {
        declareStructuralSort(tt)
        val fromSort = typeToSort(from)
        val toSort = typeToSort(to)

        z3.mkArraySort(fromSort, toSort)
      }

    case ft @ FunctionType(from, to) =>
      sorts.cached(ft) {
        val symbol = z3.mkFreshStringSymbol(ft.toString)
        z3.mkUninterpretedSort(symbol)
      }

    case other =>
      unsupported(other)
  }

  protected[z3] def toZ3Formula(expr: Expr, bindings: Map[Variable, Z3AST] = Map.empty): Z3AST = {

    def rec(ex: Expr)(implicit bindings: Map[Variable, Z3AST]): Z3AST = ex match {

      case Let(vd, e, b) =>
        val re = rec(e)
        rec(b)(bindings + (vd.toVariable -> re))

      case a @ Assume(cond, body) =>
        val (rc, rb) = (rec(cond), rec(body))
        z3.mkITE(rc, rb, z3.mkFreshConst("fail", typeToSort(body.getType)))

      case v: Variable => bindings.getOrElse(v,
        variables.cachedB(v)(z3.mkFreshConst(v.id.uniqueName, typeToSort(v.getType)))
      )

      case ite @ IfExpr(c, t, e) => z3.mkITE(rec(c), rec(t), rec(e))
      case And(exs) => z3.mkAnd(exs.map(rec): _*)
      case Or(exs) => z3.mkOr(exs.map(rec): _*)
      case Implies(l, r) => z3.mkImplies(rec(l), rec(r))
      case Not(Equals(l, r)) => z3.mkDistinct(rec(l), rec(r))
      case Not(e) => z3.mkNot(rec(e))
      case IntLiteral(v) => z3.mkInt(v, typeToSort(Int32Type))
      case IntegerLiteral(v) => z3.mkNumeral(v.toString, typeToSort(IntegerType))
      case FractionLiteral(n, d) => z3.mkNumeral(s"$n / $d", typeToSort(RealType))
      case CharLiteral(c) => z3.mkInt(c, typeToSort(CharType))
      case BooleanLiteral(v) => if (v) z3.mkTrue() else z3.mkFalse()
      case Equals(l, r) => z3.mkEq(rec( l ), rec( r ) )
      case Plus(l, r) => l.getType match {
        case BVType(_) => z3.mkBVAdd(rec(l), rec(r))
        case _ => z3.mkAdd(rec(l), rec(r))
      }
      case Minus(l, r) => l.getType match {
        case BVType(_) => z3.mkBVSub(rec(l), rec(r))
        case _ => z3.mkSub(rec(l), rec(r))
      }
      case Times(l, r) => l.getType match {
        case BVType(_) => z3.mkBVMul(rec(l), rec(r))
        case _ => z3.mkMul(rec(l), rec(r))
      }
      case Division(l, r) =>
        val (rl, rr) = (rec(l), rec(r))
        l.getType match {
          case IntegerType =>
            z3.mkITE(
              z3.mkGE(rl, z3.mkNumeral("0", typeToSort(IntegerType))),
              z3.mkDiv(rl, rr),
              z3.mkUnaryMinus(z3.mkDiv(z3.mkUnaryMinus(rl), rr))
            )
          case BVType(_) => z3.mkBVSdiv(rl, rr)
          case _ => z3.mkDiv(rl, rr)
        }
      case Remainder(l, r) => l.getType match {
        case BVType(_) => z3.mkBVSrem(rec(l), rec(r))
        case _ =>
          val q = rec(Division(l, r))
          z3.mkSub(rec(l), z3.mkMul(rec(r), q))
      }
      case Modulo(l, r) => z3.mkMod(rec(l), rec(r))
      case UMinus(e) => e.getType match {
        case BVType(_) => z3.mkBVNeg(rec(e))
        case _ => z3.mkUnaryMinus(rec(e))
      }

      case BVNot(e) => z3.mkBVNot(rec(e))
      case BVAnd(l, r) => z3.mkBVAnd(rec(l), rec(r))
      case BVOr(l, r) => z3.mkBVOr(rec(l), rec(r))
      case BVXOr(l, r) => z3.mkBVXor(rec(l), rec(r))
      case BVShiftLeft(l, r) => z3.mkBVShl(rec(l), rec(r))
      case BVAShiftRight(l, r) => z3.mkBVAshr(rec(l), rec(r))
      case BVLShiftRight(l, r) => z3.mkBVLshr(rec(l), rec(r))
      case LessThan(l, r) => l.getType match {
        case IntegerType => z3.mkLT(rec(l), rec(r))
        case RealType => z3.mkLT(rec(l), rec(r))
        case Int32Type => z3.mkBVSlt(rec(l), rec(r))
        case CharType => z3.mkBVSlt(rec(l), rec(r))
      }
      case LessEquals(l, r) => l.getType match {
        case IntegerType => z3.mkLE(rec(l), rec(r))
        case RealType => z3.mkLE(rec(l), rec(r))
        case Int32Type => z3.mkBVSle(rec(l), rec(r))
        case CharType => z3.mkBVSle(rec(l), rec(r))
      }
      case GreaterThan(l, r) => l.getType match {
        case IntegerType => z3.mkGT(rec(l), rec(r))
        case RealType => z3.mkGT(rec(l), rec(r))
        case Int32Type => z3.mkBVSgt(rec(l), rec(r))
        case CharType => z3.mkBVSgt(rec(l), rec(r))
      }
      case GreaterEquals(l, r) => l.getType match {
        case IntegerType => z3.mkGE(rec(l), rec(r))
        case RealType => z3.mkGE(rec(l), rec(r))
        case Int32Type => z3.mkBVSge(rec(l), rec(r))
        case CharType => z3.mkBVSge(rec(l), rec(r))
      }

      case u : UnitLiteral =>
        val tpe = bestRealType(u.getType)
        typeToSort(tpe)
        val constructor = constructors.toB(tpe)
        constructor()

      case t @ Tuple(es) =>
        val tpe = bestRealType(t.getType)
        typeToSort(tpe)
        val constructor = constructors.toB(tpe)
        constructor(es.map(rec): _*)

      case ts @ TupleSelect(t, i) =>
        val tpe = bestRealType(t.getType)
        typeToSort(tpe)
        val selector = selectors.toB((tpe, i-1))
        selector(rec(t))

      case c @ CaseClass(ct, args) =>
        typeToSort(ct) // Making sure the sort is defined
        val constructor = constructors.toB(ct)
        constructor(args.map(rec): _*)

      case c @ CaseClassSelector(cc, sel) =>
        val cct = cc.getType
        typeToSort(cct) // Making sure the sort is defined
        val selector = selectors.toB(cct -> c.selectorIndex)
        selector(rec(cc))

      case AsInstanceOf(expr, ct) =>
        rec(expr)

      case IsInstanceOf(e, ct) => ct.tcd match {
        case tacd: TypedAbstractClassDef =>
          tacd.descendants match {
            case Seq(tccd) =>
              rec(IsInstanceOf(e, tccd.toType))
            case more =>
              val v = Variable(FreshIdentifier("e", true), ct)
              rec(Let(v.toVal, e, orJoin(more map (tccd => IsInstanceOf(v, tccd.toType)))))
          }
        case tccd: TypedCaseClassDef =>
          typeToSort(ct)
          val tester = testers.toB(ct)
          tester(rec(e))
      }

      case f @ FunctionInvocation(id, tps, args) =>
        z3.mkApp(functionDefToDecl(getFunction(id, tps)), args.map(rec): _*)

      case fa @ Application(caller, args) =>
        val ft @ FunctionType(froms, to) = bestRealType(caller.getType)
        val funDecl = lambdas.cachedB(ft) {
          val sortSeq    = (ft +: froms).map(tpe => typeToSort(tpe))
          val returnSort = typeToSort(to)

          val name = FreshIdentifier("dynLambda").uniqueName
          z3.mkFreshFuncDecl(name, sortSeq, returnSort)
        }
        z3.mkApp(funDecl, (caller +: args).map(rec): _*)

      /**
       * ===== Set operations =====
       */
      case f @ FiniteSet(elems, base) =>
        elems.foldLeft(z3.mkEmptySet(typeToSort(base)))((ast, el) => z3.mkSetAdd(ast, rec(el)))

      case ElementOfSet(e, s) => z3.mkSetMember(rec(e), rec(s))

      case SubsetOf(s1, s2) => z3.mkSetSubset(rec(s1), rec(s2))

      case SetIntersection(s1, s2) => z3.mkSetIntersect(rec(s1), rec(s2))

      case SetUnion(s1, s2) => z3.mkSetUnion(rec(s1), rec(s2))

      case SetDifference(s1, s2) => z3.mkSetDifference(rec(s1), rec(s2))

      /**
       * ===== Bag operations =====
       */
      case fb @ FiniteBag(elems, base) =>
        typeToSort(fb.getType)
        rec(FiniteMap(elems, IntegerLiteral(0), base))

      case BagAdd(b, e) =>
        val (bag, elem) = (rec(b), rec(e))
        z3.mkStore(bag, elem, z3.mkAdd(z3.mkSelect(bag, elem), rec(IntegerLiteral(1))))

      case MultiplicityInBag(e, b) =>
        z3.mkSelect(rec(b), rec(e))

      case BagUnion(b1, b2) =>
        val plus = z3.getFuncDecl(OpAdd, typeToSort(IntegerType), typeToSort(IntegerType))
        z3.mkArrayMap(plus, rec(b1), rec(b2))

      case BagIntersection(b1, b2) =>
        rec(BagDifference(b1, BagDifference(b1, b2)))

      case BagDifference(b1, b2) =>
        val abs = z3.getAbsFuncDecl()
        val plus = z3.getFuncDecl(OpAdd, typeToSort(IntegerType), typeToSort(IntegerType))
        val minus = z3.getFuncDecl(OpSub, typeToSort(IntegerType), typeToSort(IntegerType))
        val div = z3.getFuncDecl(OpDiv, typeToSort(IntegerType), typeToSort(IntegerType))

        val all2 = z3.mkConstArray(typeToSort(IntegerType), z3.mkInt(2, typeToSort(IntegerType)))
        val withNeg = z3.mkArrayMap(minus, rec(b1), rec(b2))
        z3.mkArrayMap(div, z3.mkArrayMap(plus, withNeg, z3.mkArrayMap(abs, withNeg)), all2)

      /**
       * ===== Map operations =====
       */
      case al @ MapApply(a, i) =>
        z3.mkSelect(rec(a), rec(i))

      case al @ MapUpdated(a, i, e) =>
        z3.mkStore(rec(a), rec(i), rec(e))

      case FiniteMap(elems, default, keyTpe) =>
        val ar = z3.mkConstArray(typeToSort(keyTpe), rec(default))

        elems.foldLeft(ar) {
          case (array, (k, v)) => z3.mkStore(array, rec(k), rec(v))
        }

      case gv @ GenericValue(tp, id) =>
        typeToSort(tp)
        val constructor = constructors.toB(tp)
        constructor(rec(IntegerLiteral(id)))

      case other =>
        unsupported(other)
    }

    rec(expr)(bindings)
  }

  protected[z3] def fromZ3Formula(model: Z3Model, tree: Z3AST, tpe: Type): Expr = {

    def rec(t: Z3AST, tpe: Type): Expr = {
      val kind = z3.getASTKind(t)
      kind match {
        case Z3NumeralIntAST(Some(v)) =>
          val leading = t.toString.substring(0, 2 min t.toString.length)
          if(leading == "#x") {
            _root_.smtlib.common.Hexadecimal.fromString(t.toString.substring(2)) match {
              case Some(hexa) =>
                tpe match {
                  case Int32Type => IntLiteral(hexa.toInt)
                  case CharType  => CharLiteral(hexa.toInt.toChar)
                  case IntegerType => IntegerLiteral(BigInt(hexa.toInt))
                  case other =>
                    unsupported(other, "Unexpected target type for BV value")
                }
              case None => unsound(t, "could not translate hexadecimal Z3 numeral")
              }
          } else {
            tpe match {
              case Int32Type => IntLiteral(v)
              case CharType  => CharLiteral(v.toChar)
              case IntegerType => IntegerLiteral(BigInt(v))
              case other =>
                unsupported(other, "Unexpected type for BV value: " + other)
            } 
          }

        case Z3NumeralIntAST(None) =>
          val ts = t.toString
          if(ts.length > 4 && ts.substring(0, 2) == "bv" && ts.substring(ts.length - 4) == "[32]") {
            val integer = ts.substring(2, ts.length - 4)
            tpe match {
              case Int32Type => 
                IntLiteral(integer.toLong.toInt)
              case CharType  => CharLiteral(integer.toInt.toChar)
              case IntegerType => 
                IntegerLiteral(BigInt(integer))
              case _ =>
                reporter.fatalError("Unexpected target type for BV value: " + tpe.asString)
            }
          } else {  
            _root_.smtlib.common.Hexadecimal.fromString(t.toString.substring(2)) match {
              case Some(hexa) =>
                tpe match {
                  case Int32Type => IntLiteral(hexa.toInt)
                  case CharType  => CharLiteral(hexa.toInt.toChar)
                  case _ => unsound(t, "unexpected target type for BV value: " + tpe.asString)
                }
              case None => unsound(t, "could not translate Z3NumeralIntAST numeral")
            }
          }

        case Z3NumeralRealAST(n: BigInt, d: BigInt) => FractionLiteral(n, d)

        case Z3AppAST(decl, args) =>
          val argsSize = args.size
          if (argsSize == 0 && (variables containsB t)) {
            variables.toA(t)
          } else if(functions containsB decl) {
            val tfd = functions.toA(decl)
            assert(tfd.params.size == argsSize)
            FunctionInvocation(tfd.id, tfd.tps, args.zip(tfd.params).map{ case (a, p) => rec(a, p.getType) })
          } else if (constructors containsB decl) {
            constructors.toA(decl) match {
              case ct: ClassType =>
                CaseClass(ct, args.zip(ct.tcd.toCase.fieldsTypes).map { case (a, t) => rec(a, t) })

              case UnitType =>
                UnitLiteral()

              case TupleType(ts) =>
                tupleWrap(args.zip(ts).map { case (a, t) => rec(a, t) })

              case tp @ TypeParameter(id) =>
                val IntegerLiteral(n) = rec(args(0), IntegerType)
                GenericValue(tp, n.toInt)

              case t =>
                unsupported(t, "Woot? structural type that is non-structural")
            }
          } else {
            tpe match {
              case ft @ FunctionType(fts, tt) => lambdas.getB(ft) match {
                case None => simplestValue(ft)
                case Some(decl) => model.getFuncInterpretations.find(_._1 == decl) match {
                  case None => simplestValue(ft)
                  case Some((_, mapping, elseValue)) =>
                    val args = fts.map(tpe => ValDef(FreshIdentifier("x", true), tpe))
                    val body = mapping.foldLeft(rec(elseValue, tt)) { case (elze, (z3Args, z3Result)) =>
                      if (t == z3Args.head) {
                        val cond = andJoin((args zip z3Args.tail).map { case (vd, z3Arg) =>
                          Equals(vd.toVariable, rec(z3Arg, vd.tpe))
                        })

                        IfExpr(cond, rec(z3Result, tt), elze)
                      } else {
                        elze
                      }
                    }

                    Lambda(args, body)
                }
              }

              case MapType(from, to) =>
                model.getArrayValue(t) match {
                  case Some((z3map, z3default)) =>
                    val default = rec(z3default, to)
                    val entries = z3map.map {
                      case (k,v) => (rec(k, from), rec(v, to))
                    }

                    FiniteMap(entries.toSeq, default, from)
                  case None => unsound(t, "invalid array AST")
                }

              case BagType(base) =>
                val fm @ FiniteMap(entries, default, from) = rec(t, MapType(base, IntegerType))
                if (default != IntegerLiteral(0)) {
                  unsound(t, "co-finite bag AST")
                }

                FiniteBag(entries, base)

              case tpe @ SetType(dt) =>
                model.getSetValue(t) match {
                  case None => unsound(t, "invalid set AST")
                  case Some((_, false)) => unsound(t, "co-finite set AST")
                  case Some((set, true)) =>
                    val elems = set.map(e => rec(e, dt))
                    FiniteSet(elems.toSeq, dt)
                }

              case _ =>
                import Z3DeclKind._
                z3.getDeclKind(decl) match {
                  case OpTrue =>    BooleanLiteral(true)
                  case OpFalse =>   BooleanLiteral(false)
            //      case OpEq =>      Equals(rargs(0), rargs(1))
            //      case OpITE =>     IfExpr(rargs(0), rargs(1), rargs(2))
            //      case OpAnd =>     andJoin(rargs)
            //      case OpOr =>      orJoin(rargs)
            //      case OpIff =>     Equals(rargs(0), rargs(1))
            //      case OpXor =>     not(Equals(rargs(0), rargs(1)))
            //      case OpNot =>     not(rargs(0))
            //      case OpImplies => implies(rargs(0), rargs(1))
            //      case OpLE =>      LessEquals(rargs(0), rargs(1))
            //      case OpGE =>      GreaterEquals(rargs(0), rargs(1))
            //      case OpLT =>      LessThan(rargs(0), rargs(1))
            //      case OpGT =>      GreaterThan(rargs(0), rargs(1))
            //      case OpAdd =>     Plus(rargs(0), rargs(1))
            //      case OpSub =>     Minus(rargs(0), rargs(1))
                  case OpUMinus =>  UMinus(rec(args(0), tpe))
            //      case OpMul =>     Times(rargs(0), rargs(1))
            //      case OpDiv =>     Division(rargs(0), rargs(1))
            //      case OpIDiv =>    Division(rargs(0), rargs(1))
            //      case OpMod =>     Modulo(rargs(0), rargs(1))
                  case other => unsound(t, 
                      s"""|Don't know what to do with this declKind: $other
                          |Expected type: ${Option(tpe).map{_.asString}.getOrElse("")}
                          |Tree: $t
                          |The arguments are: $args""".stripMargin
                    )
                }
            }
          }
        case _ => unsound(t, "unexpected AST")
      }
    }

    rec(tree, bestRealType(tpe))
  }

  protected[z3] def softFromZ3Formula(model: Z3Model, tree: Z3AST, tpe: Type) : Option[Expr] = {
    try {
      Some(fromZ3Formula(model, tree, tpe))
    } catch {
      case e: Unsupported => None
      case e: UnsoundExtractionException => None
      case n: java.lang.NumberFormatException => None
    }
  }

  def symbolToFreshZ3Symbol(v: Variable): Z3AST = {
    z3.mkFreshConst(v.id.uniqueName, typeToSort(v.getType))
  }

  def assertCnstr(ast: Z3AST): Unit = solver.assertCnstr(ast)

  private def extractResult(config: Configuration)(res: Option[Boolean]) = config.cast(res match {
    case Some(true) =>
      if (config.withModel) SatWithModel(solver.getModel)
      else Sat

    case Some(false) =>
      if (config.withUnsatAssumptions) UnsatWithAssumptions(
        solver.getUnsatCore.toSet.flatMap((c: Z3AST) => z3.getASTKind(c) match {
          case Z3AppAST(decl, args) => z3.getDeclKind(decl) match {
            case OpNot => Some(args.head)
            case _ => None
          }
          case _ => None
        }))
      else Unsat

    case None => Unknown
  })

  def check(config: CheckConfiguration) = extractResult(config)(solver.check)
  def checkAssumptions(config: Configuration)(assumptions: Set[Z3AST]) =
    extractResult(config)(solver.checkAssumptions(assumptions.toSeq : _*))

  def extractModel(model: Z3Model): Map[ValDef, Expr] = variables.aToB.flatMap {
    case (v,z3ID) => (v.getType match {
      case BooleanType =>
        model.evalAs[Boolean](z3ID).map(BooleanLiteral)

      case Int32Type =>
        model.evalAs[Int](z3ID).map(IntLiteral(_)).orElse {
          model.eval(z3ID).flatMap(t => softFromZ3Formula(model, t, Int32Type))
        }

      case IntegerType =>
        model.evalAs[Int](z3ID).map(i => IntegerLiteral(BigInt(i)))

      case other => model.eval(z3ID).flatMap(t => softFromZ3Formula(model, t, other))
    }).map(v.toVal -> _)
  }

  def extractUnsatAssumptions(cores: Set[Z3AST]): Set[Expr] = {
    cores.flatMap { c =>
      variables.getA(c).orElse(z3.getASTKind(c) match {
        case Z3AppAST(decl, args) => z3.getDeclKind(decl) match {
          case OpNot => variables.getA(args.head)
          case _ => None
        }
        case ast => None
      })
    }
  }
}