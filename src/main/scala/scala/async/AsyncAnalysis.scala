package scala.async

import scala.reflect.macros.Context
import collection.mutable

private[async] final class AsyncAnalysis[C <: Context](override val c: C) extends TransformUtils(c) {
  import c.universe._

  /**
   * Analyze the contents of an `async` block in order to:
   * - Report unsupported `await` calls under nested templates, functions, by-name arguments.
   *
   * Must be called on the original tree, not on the ANF transformed tree.
   */
  def reportUnsupportedAwaits(tree: Tree) {
    new UnsupportedAwaitAnalyzer().traverse(tree)
  }

  /**
   * Analyze the contents of an `async` block in order to:
   * - Find which local `ValDef`-s need to be lifted to fields of the state machine, based
   * on whether or not they are accessed only from a single state.
   *
   * Must be called on the ANF transformed tree.
   */
  def valDefsUsedInSubsequentStates(tree: Tree): List[ValDef] = {
    val analyzer = new AsyncDefinitionUseAnalyzer
    analyzer.traverse(tree)
    analyzer.valDefsToLift.toList
  }

  private class UnsupportedAwaitAnalyzer extends super.AsyncTraverser {
    override def nestedClass(classDef: ClassDef) {
      val kind = if (classDef.symbol.asClass.isTrait) "trait" else "class"
      reportUnsupportedAwait(classDef, s"nested $kind")
    }

    override def nestedModule(module: ModuleDef) {
      reportUnsupportedAwait(module, "nested object")
    }

    override def byNameArgument(arg: Tree) {
      reportUnsupportedAwait(arg, "by-name argument")
    }

    override def function(function: Function) {
      reportUnsupportedAwait(function, "nested function")
    }

    private def reportUnsupportedAwait(tree: Tree, whyUnsupported: String) {
      val badAwaits = tree collect {
        case rt: RefTree if isAwait(rt) => rt
      }
      badAwaits foreach {
        tree =>
          c.error(tree.pos, s"await must not be used under a $whyUnsupported.")
      }
   }
  }

  private class AsyncDefinitionUseAnalyzer extends super.AsyncTraverser {
    private var chunkId = 0

    private def nextChunk() = chunkId += 1

    private var valDefChunkId = Map[Symbol, (ValDef, Int)]()

    val valDefsToLift: mutable.Set[ValDef] = collection.mutable.Set[ValDef]()

    override def traverse(tree: Tree) = {
      tree match {
        case If(cond, thenp, elsep) if tree exists isAwait =>
          traverseChunks(List(cond, thenp, elsep))
        case Match(selector, cases) if tree exists isAwait =>
          traverseChunks(selector :: cases)
        case Apply(fun, args) if isAwait(fun)              =>
          super.traverse(tree)
          nextChunk()
        case vd: ValDef                                    =>
          super.traverse(tree)
          valDefChunkId += (vd.symbol ->(vd, chunkId))
          if (isAwait(vd.rhs)) valDefsToLift += vd
        case as: Assign                                    =>
          if (isAwait(as.rhs)) {
            // TODO test the orElse case, try to remove the restriction.
            if (as.symbol != null) {
              // synthetic added by the ANF transfor
              val (vd, defBlockId) = valDefChunkId.getOrElse(as.symbol, c.abort(as.pos, "await may only be assigned to a var/val defined in the async block. " + as.symbol))
              valDefsToLift += vd
            }
          }
          super.traverse(tree)
        case rt: RefTree                                   =>
          valDefChunkId.get(rt.symbol) match {
            case Some((vd, defChunkId)) if defChunkId != chunkId =>
              valDefsToLift += vd
            case _                                               =>
          }
          super.traverse(tree)
        case _                                             => super.traverse(tree)
      }
    }

    private def traverseChunks(trees: List[Tree]) {
      trees.foreach {
        t => traverse(t); nextChunk()
      }
    }
  }
}