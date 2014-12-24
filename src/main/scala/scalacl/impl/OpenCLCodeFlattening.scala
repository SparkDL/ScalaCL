/*
 * ScalaCL - putting Scala on the GPU with JavaCL / OpenCL
 * http://scalacl.googlecode.com/
 *
 * Copyright (c) 2009-2013, Olivier Chafik (http://ochafik.com/)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Olivier Chafik nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY OLIVIER CHAFIK AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package scalacl.impl

import scala.language.postfixOps

import scalaxy.streams.TupleAnalysis
import scalaxy.streams.WhileLoops
import scalaxy.streams.WithLocalContext

import scalacl.impl.FlatCodes._

import scala.collection.mutable.ArrayBuffer

import scala.reflect.NameTransformer.{ encode, decode }
import scala.reflect.api.Universe

trait OpenCLCodeFlattening
    // extends TreeBuilders
    // with MiscMatchers
    extends TupleAnalysis
    with CommonScalaNames
    with WhileLoops
    with WithLocalContext {
  import global._
  import global.definitions._
  import Flag._

  /**
   * Phases :
   * - unique renaming
   * - tuple cartography (map symbols and treeId to TupleSlices : x._2 will be checked against x ; if is x's symbol is mapped, the resulting slice will be composed and flattened
   * - tuple + block flattening (gives (Seq[Tree], Seq[Tree]) result)
   */
  // separate pass should return symbolsDefined, symbolsUsed
  // DefTree vs. RefTree

  def fiberVariableName(rootName: Name, path: List[Int]): TermName = {
    val n = (rootName :: path.map(_ + 1)).mkString("$")
    // if (n == "item$2$1") {
    //   val ex = new RuntimeException()
    //   ex.fillInStackTrace()
    //   ex.printStackTrace()
    // }
    TermName(n)
  }

  def fiberVariableNames(rootName: Name, rootTpe: Type): Seq[(Name, Type)] = {
    if (isTupleType(rootTpe)) {
      val fiberPaths = flattenFiberPaths(rootTpe)
      val fiberTypes = flattenTypes(rootTpe)
      for ((fiberPath, fiberType) <- fiberPaths.zip(fiberTypes)) yield {
        fiberVariableName(rootName, fiberPath) -> fiberType
      }
    } else {
      Seq(rootName -> rootTpe)
    }
  }

  def getDefAndRefTrees(tree: Tree) = {
    val defTrees = new ArrayBuffer[DefTree]()
    val refTrees = new ArrayBuffer[RefTree]()
    new Traverser {
      override def traverse(tree: Tree): Unit = {
        if (tree.symbol != null && tree.symbol != NoSymbol)
          tree match {
            case dt: DefTree => defTrees += dt
            case rt: RefTree => refTrees += rt
            case _ =>
          }
        super.traverse(tree)
      }
    }.traverse(tree)
    (defTrees.toArray, refTrees.toArray)
  }
  def renameDefinedSymbolsUniquely(tree: Tree) = {
    val (defTrees, refTrees) = getDefAndRefTrees(tree)
    val definedSymbols = defTrees.collect { case d if d.name != null => d.symbol -> d.name }.toMap
    val usedIdentSymbols = refTrees.collect { case ident @ Ident(name) => ident.symbol -> name }.toMap

    val outerSymbols = usedIdentSymbols.keys.toSet.diff(definedSymbols.keys.toSet)
    val nameCollisions = (definedSymbols ++ usedIdentSymbols).groupBy(_._2).filter(_._2.size > 1)
    val renamings = (nameCollisions.flatMap(_._2) map {
      case (sym, name) => //if !internal.isFreeTerm(sym) =>
        val newName: Name = N(fresh(name.toString))
        (sym, newName)
    }).toMap

    // println(s"""
    //   definedSymbols = $definedSymbols
    //   usedIdentSymbols = $usedIdentSymbols
    //   outerSymbols = $outerSymbols
    //   nameCollisions = $nameCollisions 
    //   renamings = $renamings
    // """)

    if (renamings.isEmpty)
      tree
    else {
      // println("ABOUT TO RENAME: " + renamings)
      new Transformer {
        // TODO rename the symbols themselves ??
        override def transform(tree: Tree): Tree = {
          def check(newTree: Tree) =
            typecheck(newTree, pt = tree.tpe)

          renamings.get(tree.symbol).map(newName => {
            tree match {
              case ValDef(mods, name, tpt, rhs) =>
                check(treeCopy.ValDef(tree, mods, newName, super.transform(tpt), super.transform(rhs)))
              case DefDef(mods, name, tparams, vparams, tpt, rhs) =>
                check(treeCopy.DefDef(tree, mods, newName, tparams, vparams, super.transform(tpt), super.transform(rhs)))
              case Ident(name) =>
                check(treeCopy.Ident(tree, newName))
              case _ =>
                super.transform(tree)
            }
          }).getOrElse(super.transform(tree))
        }
      }.transform(tree)
    }
  }

  def flatten(
    tree: Tree,
    inputSymbols: Seq[(Symbol, Type)] = Seq(),
    owner: Symbol = NoSymbol,
    renameSymbols: Boolean = true): FlatCode[Tree] = {
    val actualTree =
      if (renameSymbols)
        renameDefinedSymbolsUniquely(tree)
      else
        tree
    val tupleAnalyzer = new TupleAnalyzer(actualTree)
    val flattener = new TuplesAndBlockFlattener(tupleAnalyzer)
    // println("INITIAL: " + tree)
    val result = flattener.flattenTuplesAndBlocksWithInputSymbols(actualTree, inputSymbols, owner)
    // println("RENAMED: " + actualTree)
    // println("RESULT: " + result)
    result
  }

  object NumberConversion {
    private[this] val namesToTypes = Map(
      "toSizeT" -> "size_t",
      "toLong" -> "long",
      "toInt" -> "int",
      "toShort" -> "short",
      "toByte" -> "char",
      "toChar" -> "short",
      "toDouble" -> "double",
      "toFloat" -> "float"
    )

    def unapply(tree: Tree): Option[(Tree, String)] = tree match {
      case Select(expr, n) =>
        namesToTypes.get(n.toString).map(expr -> _)
      case _ =>
        None
    }
  }

  class TuplesAndBlockFlattener(val tupleAnalyzer: TupleAnalyzer) {
    import tupleAnalyzer._

    def isOpenCLIntrinsicTuple(components: List[Type]) = {
      components.size match {
        case 2 | 4 | 8 | 16 =>
          components.distinct.size == 1
        case _ =>
          false
      }
    }

    var sliceReplacements = new scala.collection.mutable.HashMap[TupleSlice, TreeGen]()

    def isUnitOrNoType(tpe: Type) = tpe == NoType || tpe == UnitTpe

    def makeValuesSideEffectFree(code: FlatCode[Tree], symbolOwner: Symbol) = {
      //code /*
      var hasNewStatements = false
      val vals = for (value <- code.values) yield {
        value match {
          case Select(ScalaMathFunction(_, _, _), N("toFloat")) =>
            // special case for non-double math :
            // exp(20: Float).toFloat
            (Seq(), value)
          case ScalaMathFunction(_, _, _) => //Apply(f @ Select(left, name), args) if left.toString == "scala.math.package" =>
            // TODO this is not fair : ScalaMathFunction should have worked here !!!
            (Seq(), value)
          /*case Apply(s @ Select(left, name), args) if NameTransformer.decode(name.toString) match {
            case op @ ("+" | "-" | "*" | "/" | "%" | "^" | "^^" | "&" | "&&" | "|" | "||" | "<<" | ">>" | "==" | "<" | ">" | "<=" | ">=" | "!=") =>
              true
            case n if left.toString == "scala.math.package" =>
              true
            case _ =>
              false
        } =>
          (Seq(), value)*/
          case Ident(_) | Select(_, _) | ValDef(_, _, _, _) | Literal(_) | NumberConversion(_, _) | Typed(_, _) | Apply(_, List(_)) =>
            // already side-effect-free (?)
            (Seq(), value)
          case _ if isUnitOrNoType(getType(value)) =>
            (Seq(), value)
          case _ =>
            assert(getType(value) != NoType, value + ": " + value.getClass.getName) // + " = " + nodeToString(value) + ")")
            // val tempVar = q"var tmp: ${value.tpe} = $value" // TODO fresh
            val tempVar = newVal("tmp", value, value.tpe)
            //println("Creating temp variable " + tempVar.symbol + " for " + value)
            hasNewStatements = true
            for (slice <- getTreeSlice(value))
              setSlice(tempVar.definition, slice)

            (Seq(tempVar.definition), tempVar())
        }
      }
      if (!hasNewStatements)
        code
      else
        FlatCode[Tree](
          code.outerDefinitions,
          code.statements ++ vals.flatMap(_._1),
          vals.map(_._2)
        )
    }

    def flattenTuplesAndBlocksWithInputSymbols(tree: Tree, inputSymbols: Seq[(Symbol, Type)], symbolOwner: Symbol): FlatCode[Tree] = {

      val top @ FlatCode(outerDefinitions, statements, values) = flattenTuplesAndBlocks(tree)(symbolOwner)
      def lastMinuteReplace = {
        val trans = new Transformer {
          override def transform(tree: Tree) =
            replace(super.transform(tree))
        }
        trans transform _
      }
      FlatCode[Tree](
        outerDefinitions map lastMinuteReplace,
        statements map lastMinuteReplace,
        values map lastMinuteReplace
      )
    }
    def replace(tree: Tree): Tree =
      replaceValues(tree) match {
        case Seq(unique) => unique
        case _ => tree
      }

    def replaceValues(tree: Tree): Seq[Tree] = tree match {
      case ValDef(_, _, _, _) =>
        Seq(tree)
      case _ =>
        try {
          getTreeSlice(tree, recursive = true) match {
            case Some(slice) if slice.baseSymbol != tree.symbol && isTupleType(slice.baseSymbol.typeSignature) =>
              sliceReplacements.get(slice) match {
                case Some(identGen) =>
                  // println(s"""
                  // SLICE:
                  //   tree: $tree
                  //   slice: $slice
                  //   id: ${identGen()}
                  // """)
                  Seq(identGen())
                case None =>
                  val subs = for (i <- 0 until slice.sliceLength) yield {
                    Ident(fiberVariableName(slice.baseSymbol.name, List(i))
                    )
                  }

                  // println(s"""
                  // SLICE:
                  //   tree: $tree
                  //   slice: $slice
                  //   subs: $subs
                  // """)
                  subs
              }
            case _ =>
              Seq(tree)
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            Seq(tree)
        }
    }

    def flattenTuplesAndBlocks(initialTree: Tree, sideEffectFree: Boolean = true)(implicit symbolOwner: Symbol): FlatCode[Tree] = {
      val tree = initialTree //replace(initialTree)
      // If the tree is mapped to a slice and that slice is mapped to a replacement, then replace the tree by an ident to the corresponding name+symbol
      val res: FlatCode[Tree] = tree match {
        case Block(statements, value) =>
          // Flatten blocks up
          statements.map(flattenTuplesAndBlocks(_).noValues).foldLeft(FlatCode[Tree]())(_ ++ _) >> flattenTuplesAndBlocks(value)

        case TupleCreation(components) =>
          val sub = components.map(flattenTuplesAndBlocks(_))
          FlatCode[Tree](
            sub.flatMap(_.outerDefinitions),
            sub.flatMap(_.statements),
            sub.flatMap(_.values)
          )
        case TupleComponent(target, i) => //if getTreeSlice(target).collect(sliceReplacements) != None =>
          getTreeSlice(target, recursive = true) match {
            case Some(slice) =>
              sliceReplacements.get(slice) match {
                case Some(rep) =>
                  FlatCode[Tree](Seq(), Seq(), Seq(rep()))
                case None =>
                  FlatCode[Tree](Seq(), Seq(), Seq(tree))
              }
            case _ =>
              FlatCode[Tree](Seq(), Seq(), Seq(tree))
          }
        case Ident(name: TermName) =>
          val tpe = normalize(tree.tpe) match {
            case typeRef @ TypeRef(_, _, List(elementType)) if typeRef <:< typeOf[scalacl.CLArray[_]] =>
              elementType
            case t => t
          }
          if (isTupleType(tpe)) {
            val identGen = () => Ident(name)
            val fiberPaths = flattenFiberPaths(tpe)
            val fiberValues = fiberVariableNames(name, tpe).map(x => Ident(x._1))
            FlatCode[Tree](Seq(), Seq(), fiberValues)
          } else {
            FlatCode[Tree](Seq(), Seq(), Seq(tree))
          }
        case Literal(_) =>
          // TODO?
          FlatCode[Tree](Seq(), Seq(), Seq(tree))
        case s @ Select(This(targetClass), name) =>
          FlatCode[Tree](
            Seq(),
            Seq(),
            Seq(Ident(name))
          )
        case Select(target, name) =>
          //println("CONVERTING select " + tree)
          val FlatCode(defs, stats, vals) = flattenTuplesAndBlocks(target, sideEffectFree = getType(target) != NoType)
          FlatCode[Tree](
            defs,
            stats,
            vals.map(v => Select(v, TermName(decode(name.toString))))
          )
        case Assign(lhs, rhs) =>
          merge(Seq(lhs, rhs).map(flattenTuplesAndBlocks(_)): _*) {
            case Seq(l, r) =>
              Seq(Assign(l, r))
          }
        case Apply(Select(target, N("update")), List(index, value)) if isTupleType(getType(value)) =>
          val targetTpe = normalize(target.tpe).asInstanceOf[TypeRef]
          // val indexVal = q"val index: ${index.tpe} = $index" // TODO fresh
          val indexVal = newVal("index", index, index.tpe)

          val flatTarget = flattenTuplesAndBlocks(target)
          val flatValue = flattenTuplesAndBlocks(value)
          val res = FlatCode[Tree](
            flatTarget.outerDefinitions ++ flatValue.outerDefinitions,
            flatTarget.statements ++ Seq(indexVal.definition) ++ flatValue.statements,
            for ((t, v) <- flatTarget.values.zip(flatValue.values)) yield {
              Apply(Select(t, N("update")), List(indexVal(), v))
            }
          )
          // println("UPDATE TUP(" + getType(value) + ") tree = " + tree + ", res = " + res)
          res
        case Apply(ident @ Ident(functionName), args) =>
          val f = args.map(flattenTuplesAndBlocks(_))
          // TODO assign vals to new vars before the calls, to ensure a correct evaluation order !
          FlatCode[Tree](
            f.flatMap(_.outerDefinitions),
            f.flatMap(_.statements),
            Seq(
              typecheck(
                Apply(
                  Ident(functionName),
                  // Ident(ident.symbol),
                  f.flatMap(_.values)
                ),
                pt = tree.tpe
              )
            )
          )
        case Apply(target, args) =>
          //println("CONVERTING apply " + tree)
          val fc1 @ FlatCode(defs1, stats1, vals1) =
            flattenTuplesAndBlocks(target, sideEffectFree = getType(target) != NoType)

          val argsConv = args.map(flattenTuplesAndBlocks(_))
          val tpes = flattenTypes(getType(tree))
          // TODO assign vals to new vars before the calls, to ensure a correct evaluation order !
          // TODO test this extensively!!!!
          val result = FlatCode[Tree](
            defs1 ++ argsConv.flatMap(_.outerDefinitions),
            stats1 ++ argsConv.flatMap(_.statements),
            vals1.zip(tpes).map {
              case (target, tpe) =>
                val args = argsConv.map(_.values)
                Apply(target, args.flatten)
            }
          )
          // println(s"CONVERTED apply $tree\n\tresult = $result, \n\ttpes = $tpes, \n\targs = $args, \n\targsConv = $argsConv, \n\tvals1 = $vals1, fc1 = $fc1")
          result
        case f @ DefDef(_, _, _, _, _, _) =>
          FlatCode[Tree](Seq(f), Seq(), Seq())
        case WhileLoop(condition, content) =>
          // TODO clean this up !!!
          val flatCondition = flattenTuplesAndBlocks(condition)
          val flatContent = content.map(flattenTuplesAndBlocks(_)).reduceLeft(_ >> _)
          val Seq(flatConditionValue) = flatCondition.values
          FlatCode[Tree](
            flatCondition.outerDefinitions ++ flatContent.outerDefinitions,
            flatCondition.statements ++
              Seq(
                WhileLoop(
                  flatConditionValue,
                  flatContent.statements.toList ++ flatContent.values
                )
              ),
            Seq()
          )
        case If(condition, thenDo, otherwise) =>
          // val (a, b) = if ({ val d = 0 ; d != 0 }) (1, d) else (2, 0)
          // ->
          // val d = 0
          // val condition = d != 0
          // val a = if (condition) 1 else 2
          // val b = if (condition) d else 0
          val FlatCode(dc, sc, Seq(vc)) = flattenTuplesAndBlocks(condition)
          assert(getType(vc) != NoType, s"No type on $vc")
          val conditionVar = newVal("condition", vc, vc.tpe)

          val fct @ FlatCode(Seq(), st, vt) = flattenTuplesAndBlocks(thenDo)
          val fco @ FlatCode(Seq(), so, vo) = flattenTuplesAndBlocks(otherwise)

          FlatCode[Tree](
            dc,
            sc :+ conditionVar.definition,
            (st, so) match {
              case (Seq(), Seq()) =>
                vt.zip(vo).map { case (t, o) => If(conditionVar(), t, o) } // pure (cond ? then : otherwise) form, possibly with tuple values
              case _ =>
                Seq(
                  If(
                    conditionVar(),
                    Block(st.toList ++ vt.toList, q"()"),
                    Block(so.toList ++ vo.toList, q"()")
                  )
                )
            }
          )
        case Typed(expr, tpt) =>
          flattenTuplesAndBlocks(expr).mapValues(_.map(Typed(_, tpt)))
        case ValDef(paramMods, paramName, tpt, rhs) =>
          // val isVal = !paramMods.hasFlag(MUTABLE)
          // val p = {
          //   val x = 10
          //   (x, x + 2)
          // }
          // ->
          // val x = 10
          // val p_1 = x
          // val p_2 = x + 2
          val FlatCode(defs, stats, values) = flattenTuplesAndBlocks(replace(rhs))(tree.symbol)
          //val flatValues = values.flatMap(replaceValues)
          val tpe = getType(tree)
          assert(tpe != null, "tpe is null")

          val fiberTypes = flattenTypes(tpe)
          val fiberPaths = flattenFiberPaths(tpe)
          assert(values.size == fiberTypes.size, "values = " + values + ", fiberTypes = " + fiberTypes + ", fiberPaths = " + fiberPaths + ", tree = " + tree + ", tree.symbol = " + tree.symbol)

          FlatCode[Tree](
            defs,
            stats ++
              fiberTypes.zip(fiberPaths).zip(values).map({
                case ((fiberType, fiberPath), value) =>
                  val fiberName = fiberVariableName(paramName, fiberPath)

                  ValDef(paramMods, fiberName, TypeTree(fiberType), replace(value))
              }),
            Seq()
          )

        case Match(selector, List(CaseDef(pat, guard, body))) =>
          def extract(tree: Tree): Tree = tree match {
            case Typed(expr, tpt) => extract(expr)
            case Annotated(annot, arg) => extract(arg)
            case _ => tree
          }
          getTreeSlice(selector, recursive = true).orElse(getTreeSlice(extract(selector), recursive = true)) match {
            case Some(slice) =>
              val subMatcher = new BoundTuple(slice)
              val subMatcher(m) = pat

              for ((sym, subSlice) <- m) {
                val info = getTupleInfo(sym.typeSignature)
                val boundSlice = TupleSlice(sym, 0, info.flattenTypes.size)
                val rep = subSlice.toTreeGen(tupleAnalyzer)
                setSlice(sym, subSlice)
                if (subSlice.sliceLength == 1)
                  sliceReplacements ++= Seq(boundSlice -> subSlice.toTreeGen(tupleAnalyzer))
              }

              flattenTuplesAndBlocks(body)
            case _ =>
              throw new RuntimeException("Unable to connect the matched pattern with its corresponding single case")
          }
        case EmptyTree =>
          // println("CodeFlattening  -  WARNING EmptyTree! Should this ever happen?")
          FlatCode[Tree](Seq(), Seq(), Seq())
        case _ =>
          // new RuntimeException().printStackTrace()
          assert(assertion = false, "Case not handled in tuples and blocks flattening : " + tree + ": " + tree.getClass.getName)
          FlatCode[Tree](Seq(), Seq(), Seq())
      }
      val ret = if (sideEffectFree)
        makeValuesSideEffectFree(
          res,
          symbolOwner
        )
      else
        res

      val rep = ret.mapValues(s => s.flatMap(replaceValues))
      if (false) {
        println()
        println("tree (" + tree.getClass + ") = \n\t" + tree.toString().replaceAll("\n", "\n\t"))
        println("res = \n\t" + res.toString.replaceAll("\n", "\n\t"))
        println("ret = \n\t" + ret.toString.replaceAll("\n", "\n\t"))
        println("rep = \n\t" + rep.toString.replaceAll("\n", "\n\t"))
      }

      //rep.printDebug("rep")
      rep
    }
  }
}
