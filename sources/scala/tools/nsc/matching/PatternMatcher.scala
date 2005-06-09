/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author buraq
 */
// $Id$

package scala.tools.nsc.matching ;

import scala.tools.util.Position;

trait PatternMatchers: (TransMatcher with PatternNodes) with PatternNodeCreator {

  import global._;
  import symtab.Flags;

 class PatternMatcher  {



  protected var optimize = true;
  protected var delegateSequenceMatching = false;
  protected var doBinding = true;

  /** the owner of the pattern matching expression
   */
  var owner:Symbol = _ ;

  /** the selector expression
   */
  protected var selector: Tree = _;

  /** the root of the pattern node structure
   */
  protected var root: PatternNode = _;

  /** the symbol of the result variable
   */
  protected var resultVar: Symbol = _;

  def defs = definitions;
  /** init method, also needed in subclass AlgebraicMatcher
   */
  def initialize(selector: Tree, owner: Symbol, doBinding: Boolean): Unit = {

    /*
    this.mk = new PatternNodeCreator {
      val global = PatternMatcher.this.global;
      val unit = PatternMatcher.this.unit;
      val owner = PatternMatcher.this.owner;
    }
    */
    /*
    this.cf = new CodeFactory {
      val global = PatternMatcher.this.global;
      val unit = PatternMatcher.this.unit;
      val owner = PatternMatcher.this.owner;
    }
    */
    this.root = pConstrPat(selector.pos, selector.tpe.widen);
    this.root.and = pHeader(selector.pos,
                              selector.tpe.widen,
                              Ident(root.symbol));
    this.resultVar = owner.newVariable(Flags.MUTABLE,
                                       "result");
    this.owner = owner;
    this.selector = selector;
    this.optimize = this.optimize & (settings.target == "jvm");
    this.doBinding = doBinding;
  }

  /** pretty printer
   */
  def print(): Unit = { Console.println (
    root.and.print("", new StringBuffer()).toString()
  )}

  /** enters a sequence of cases into the pattern matcher
   */
  def construct(cases: List[Tree]): Unit = {
    cases foreach enter;
  }

  /** enter a single case into the pattern matcher
   */
  protected def enter(caseDef: Tree): Unit = {
    caseDef match {
      case CaseDef(pat, guard, body) =>
        val env = new CaseEnv;
        // PatternNode matched = match(pat, root);
        val target = enter1(pat, -1, root, root.symbol, env);
        // if (target.and != null)
        //     unit.error(pat.pos, "duplicate case");
      if (null == target.and)
        target.and = pBody(caseDef.pos, env.getBoundVars(), guard, body);
      else if (target.and.isInstanceOf[Body])
        updateBody(target.and.asInstanceOf[Body], env.getBoundVars(), guard, body);
      else
        cunit.error(pat.pos, "duplicate case");
    }
  }

  protected def updateBody(tree: Body, bound: Array[ValDef], guard: Tree , body: Tree): Unit = {
    if (tree.guard(tree.guard.length - 1) == EmptyTree) {
      //unit.error(body.pos, "unreachable code");
    } else {
      val bd = new Array[Array[ValDef]](tree.bound.length + 1);
      val ng = new Array[Tree](tree.guard.length + 1);
      val nb = new Array[Tree](tree.body.length + 1);
      System.arraycopy(tree.bound, 0, bd, 0, tree.bound.length);
      System.arraycopy(tree.guard, 0, ng, 0, tree.guard.length);
      System.arraycopy(tree.body, 0, nb, 0, tree.body.length);
      bd(bd.length - 1) = bound;
      ng(ng.length - 1) = guard;
      nb(nb.length - 1) = body;
      tree.bound = bd ;
      tree.guard = ng ;
      tree.body = nb ;
    }
  }

  protected def patternArgs(tree: Tree):List[Tree] = {
    tree match {
      case Bind(_, pat) =>
        patternArgs(pat);
      case Apply(_, args) =>
        if ( isSeqApply(tree.asInstanceOf[Apply])  && !delegateSequenceMatching)
          args(0) match {
            case Sequence(ts) =>
              ts;
            case _ =>
              args;
          }
        else args
      case Sequence(ts) if (!delegateSequenceMatching) =>
        ts;
      case _ =>
        List();
    }
  }

  /** returns true if apply is a "sequence apply". analyzer inserts Sequence nodes if something is a
   *
   *  - last update: discussion with Martin 2005-02-18
   *
   *  - if true, tree.fn must be ignored. The analyzer ensures that the selector will be a subtype
   *    of fn; it thus assigns the expected type from the context (which is surely a subtype,
   *    but may have different flags etc.
   *
   *  - so should be
   *     (( tree.args.length == 1 ) && tree.args(0).isInstanceOf[Sequence])
   *     but fails
   */
  protected def isSeqApply( tree: Apply  ): Boolean =
    (( tree.args.length == 1 ) && tree.args(0).isInstanceOf[Sequence])
    && (tree.tpe.symbol.flags & Flags.CASE) == 0;

  protected def patternNode(tree:Tree , header:Header , env: CaseEnv ): PatternNode  = {
    //Console.println("patternNode("+tree+","+header+")");
    //Console.println("tree.tpe"+tree.tpe);
    tree match {
      case Bind(name, Typed(Ident( nme.WILDCARD ), tpe)) => // x@_:Type
        if (isSubType(header.getTpe(),tpe.tpe)) {
          val node = pDefaultPat(tree.pos, tpe.tpe);
          env.newBoundVar( tree.symbol, tree.tpe, header.selector );
          node;
        } else {
          val node = pConstrPat(tree.pos, tpe.tpe);
          env.newBoundVar( tree.symbol, tree.tpe, Ident( node.casted ));
          node;
        }

      case Bind(name, Ident(nme.WILDCARD)) => // x @ _
        val node = pDefaultPat(tree.pos, header.getTpe());
        if ((env != null) && (tree.symbol != defs.PatternWildcard))
          env.newBoundVar( tree.symbol, tree.tpe, header.selector);
        node;

      case Bind(name, pat) =>
        val node = patternNode(pat, header, env);
        if ((env != null) && (tree.symbol != defs.PatternWildcard)) {
          val casted = node.symbol;
          val theValue =  if (casted == NoSymbol) header.selector else Ident( casted);
          env.newBoundVar(tree.symbol, tree.tpe, theValue);
        }
       node;

      case t @ Apply(fn, args) =>             // pattern with args
        if (isSeqApply(t)) {
          if (!delegateSequenceMatching) {
            args(0) match {
              case Sequence(ts)=>
                pSequencePat(tree.pos, tree.tpe, ts.length);
            }
          } else {
            val res = pConstrPat(tree.pos, tree.tpe);
            res.and = pHeader(tree.pos, header.getTpe(), header.selector);
            res.and.and = pSeqContainerPat(tree.pos, tree.tpe, args(0));
            res;
          }
        } else if ((fn.symbol != null) &&
                   fn.symbol.isStable &&
                   !(fn.symbol.isModule &&
                     ((fn.symbol.flags & Flags.CASE) != 0))) {
                       pVariablePat(tree.pos, tree);
                     }
           else {
             /*
            Console.println("apply but not seqApply");
            Console.println("tree.tpe="+tree.tpe);
            Console.println("tree.symbol="+tree.symbol);
             */
             pConstrPat(tree.pos, tree.tpe);
           }
        case t @ Typed(ident, tpe) =>       // variable pattern
          val doTest = isSubType(header.getTpe(),tpe.tpe);
          val node = {
            if(doTest)
              pDefaultPat(tree.pos, tpe.tpe)
            else
              pConstrPat(tree.pos, tpe.tpe);
          }
          if ((null != env) && (ident.symbol != defs.PatternWildcard))
            node match {
              case ConstrPat(casted) =>
                env.newBoundVar(t.expr.symbol,
                                tpe.tpe,
                                Ident( casted ));
              case _ =>
                env.newBoundVar(t.expr.symbol,
                                tpe.tpe,
                                {if(doTest) header.selector else Ident(node.asInstanceOf[ConstrPat].casted)});
            }
          node;

        case Ident(name) =>               // pattern without args or variable
            if (tree.symbol == defs.PatternWildcard)
              pDefaultPat(tree.pos, header.getTpe());
            else if (tree.symbol.isPrimaryConstructor) {
              scala.Predef.error("error may not happen"); // Burak

            } else if (treeInfo.isVariableName(name)) {//  Burak
              scala.Predef.error("this may not happen"); // Burak
            } else
              pVariablePat(tree.pos, tree);

        case Select(_, name) =>                                  // variable
            if (tree.symbol.isPrimaryConstructor)
                pConstrPat(tree.pos, tree.tpe);
            else
                pVariablePat(tree.pos, tree);

        case Literal(Constant(value)) =>
            pConstantPat(tree.pos, tree.tpe, value);

        case Sequence(ts) =>
            if ( !delegateSequenceMatching ) {
                pSequencePat(tree.pos, tree.tpe, ts.length);
            } else {
                pSeqContainerPat(tree.pos, tree.tpe, tree);
            }
        case Alternative(ts) =>
          if(ts.length < 2)
            scala.Predef.error("ill-formed Alternative");
          val subroot = pConstrPat(header.pos, header.getTpe());
          subroot.and = pHeader(header.pos, header.getTpe(), header.selector.duplicate);
            val subenv = new CaseEnv;
          var i = 0; while(i < ts.length) {
            val target = enter1(ts(i), -1, subroot, subroot.symbol, subenv);
            target.and = pBody(tree.pos);
            i = i + 1
          }
          pAltPat(tree.pos, subroot.and.asInstanceOf[Header]);

        case _ =>
          scala.Predef.error("unit = " + cunit + "; tree = "+tree);
    }
  }

  protected def enter(pat: Tree, index: Int, target: PatternNode, casted: Symbol, env: CaseEnv ): PatternNode = {
    target match {
      case ConstrPat(newCasted) =>
        enter1(pat, index, target, newCasted, env);
      case SequencePat(newCasted, len) =>
        enter1(pat, index, target, newCasted, env);
      case _ =>
        enter1(pat, index, target, casted, env);
    }
  }

  private def newHeader(pos: Int, casted: Symbol, index: Int): Header = {
    //Console.println("newHeader(pos,"+casted+","+index+")");
    //Console.println("  casted.tpe"+casted.tpe);
    val ident = Ident(casted);
    if (casted.pos == Position.FIRSTPOS) {
      //Console.println("FIRSTPOS");
      val t = Apply(Select( ident, defs.functionApply( 1 )),
                    List( Literal( index ) ));
      val seqType = t.tpe;
      pHeader( pos, seqType, t );
    } else {
      //Console.println("NOT FIRSTPOS");
      val ts = casted.tpe.symbol.asInstanceOf[ClassSymbol]
        .caseFieldAccessors(index);
      //Console.println("ts="+ts);
      val accType = casted.tpe.memberType(ts);
      val accTree = Select(ident, ts); // !!!
      accType match {
        // scala case accessor
        case MethodType(_, _) =>
          pHeader(pos, accType.resultType, Apply(accTree, List()));
        // jaco case accessor
        case _ =>
          pHeader(pos, accType, accTree);
      }
    }
  }

  /** main enter function
   *
   *  invariant: ( curHeader == (Header)target.and ) holds
   */
  protected def enter1(pat: Tree, index: Int, target: PatternNode, casted: Symbol,  env: CaseEnv): PatternNode = {
    //System.err.println("enter(" + pat + ", " + index + ", " + target + ", " + casted + ")");
    val patArgs = patternArgs(pat);        // get pattern arguments
    var curHeader = target.and.asInstanceOf[Header];    // advance one step in intermediate representation
    if (curHeader == null) {                  // check if we have to add a new header
      //assert index >= 0 : casted;
      if (index < 0) { scala.Predef.error("error entering:" + casted); return null }
      target.and = {curHeader = newHeader(pat.pos, casted, index); curHeader};
      curHeader.or = patternNode(pat, curHeader, env);
      enter(patArgs, curHeader.or, casted, env);
    }
    else {
      // find most recent header
      while (curHeader.next != null)
      curHeader = curHeader.next;
      // create node
      var patNode = patternNode(pat, curHeader, env);
      var next: PatternNode = curHeader;
      // add branch to curHeader, but reuse tests if possible
      while (true) {
        if (next.isSameAs(patNode)) {           // test for patNode already present --> reuse
          // substitute... !!!
          patNode match {
            case ConstrPat(ocasted) =>
              env.substitute(ocasted, Ident(next.asInstanceOf[ConstrPat].casted));
            case _ =>
          }
          return enter(patArgs, next, casted, env);
        } else if (next.isDefaultPat() ||           // default case reached, or
                   ((next.or == null) &&            //  no more alternatives and
                    (patNode.isDefaultPat() || next.subsumes(patNode)))) {
                      // new node is default or subsumed
                      var header = pHeader(patNode.pos,
                                             curHeader.getTpe(),
                                             curHeader.selector);
                      {curHeader.next = header; header};
                      header.or = patNode;
                      return enter(patArgs,
                                   patNode,
                                   casted,
                                   env);
                    }
          else if (next.or == null) {
            return enter(patArgs, {next.or = patNode; patNode}, casted, env); // add new branch
          } else
            next = next.or;
      }
      error("must not happen");
      null
    }
  }

  /** calls enter for an array of patterns, see enter
   */
  protected def enter(pats:List[Tree], target1: PatternNode , casted1: Symbol  , env: CaseEnv): PatternNode = {
    var target = target1;
    var casted = casted1;
    target match {
      case ConstrPat(newCasted) =>
        casted = newCasted;
      case SequencePat(newCasted, len) =>
        casted = newCasted;
      case _ =>
    }
    var i = 0; while(i < pats.length) {
      target = enter1(pats(i), i, target, casted, env);
      i = i + 1
    }
    target;
  }

    protected def nCaseComponents(tree: Tree): int = {
        tree match {
          case Apply(fn, _) =>
            val tpe = tree.tpe.symbol.primaryConstructor.tpe;
          //Console.println("~~~ " + tree.type() + ", " + tree.type().symbol.primaryConstructor());
            tpe match {
                // I'm not sure if this is a good idea, but obviously, currently all case classes
                // without constructor arguments have type NoType
            case NoType =>
                error("this cannot happen");
              0
            case MethodType(args, _) =>
                args.length;
            case PolyType(tvars, MethodType(args, _)) =>
                args.length;
            case PolyType(tvars, _) =>
                0;
            case _ =>
              error("not yet implemented;" +
                    "pattern matching for " + tree + ": " + tpe);
            }
        }
      return 0;
    }


    //////////// generator methods

  def toTree(): global.Tree = {
      if (optimize && isSimpleIntSwitch())
        intSwitchToTree();

      else /* if (false && optimize && isSimpleSwitch())
        switchToTree();
      else */ {
        //print();
        generalSwitchToTree();
      }
    }

  case class Break(res:Boolean) extends java.lang.Throwable;
  case class Break2() extends java.lang.Throwable;

    protected def isSimpleSwitch(): Boolean  = {
      print();
      var patNode = root.and;
      while (patNode != null) {
        var node = patNode;
        while (({node = node.or; node}) != null) {
          node match {
                case VariablePat(tree) =>
                  Console.println(((tree.symbol.flags & Flags.CASE) != 0));
                case ConstrPat(_) =>
                  Console.println(node.getTpe().toString() + " / " + ((node.getTpe().symbol.flags & Flags.CASE) != 0));
                    var inner = node.and;
                    def funct(inner: PatternNode): Boolean = {
                      //outer: while (true) {
                      inner match {
                        case _h:Header =>
                          if (_h.next != null)
                            throw Break(false);
                          funct(inner.or)

                        case DefaultPat() =>
                          funct(inner.and);

                        case b:Body =>
                          if ((b.guard.length > 1) ||
                              (b.guard(0) != EmptyTree))
                            throw Break(false);

                          throw Break2() // break outer
                        case _ =>
                          Console.println(inner);
                          throw Break(false);
                      }
                    }
                    var res = false;
                    var set = false;
                    try {
                      funct(inner)
                    } catch {
                      case Break(res1) =>
                        res = res1;
                        set = true;
                      case Break2() =>
                    }
                    if(set) return res;
            case _ =>
              return false;
          }
        }
        patNode = patNode.nextH();
      }
      return true;
    }

  protected def isSimpleIntSwitch(): Boolean = {
    if (isSameType(selector.tpe.widen, defs.IntClass.info)) {
      var patNode = root.and;
      while (patNode != null) {
        var node = patNode;
        while (({node = node.or; node}) != null) {
          node match {
            case ConstantPat(_) => ;
            case _ =>
              return false;
          }
          node.and match {
            case _b:Body =>
              if ((_b.guard.length > 1) ||
                  (_b.guard(0) != EmptyTree) ||
                  (_b.bound(0).length > 0))
                return false;
            case _ =>
              return false;
          }
        }
        patNode = patNode.nextH();
      }
      return true;
    } else
      return false;
  }

  class TagBodyPair(tag1: Int, body1: Tree, next1:TagBodyPair ) {

    var tag: int = tag1;
    var body: Tree = body1;
    var next: TagBodyPair = next1;

    def length(): Int = {
      if (null == next) 1 else (next.length() + 1);
    }
  }

  protected def numCases(patNode1: PatternNode): Int = {
    var patNode = patNode1;
    var n = 0;
    while (({patNode = patNode.or; patNode}) != null)
    patNode match {
      case DefaultPat() => ;
      case _ =>
        n = n + 1;
    }
    n;
  }

  protected def defaultBody(patNode1: PatternNode, otherwise: Tree ): Tree = {
    var patNode = patNode1;
    while (patNode != null) {
      var node = patNode;
      while (({node = node.or; node}) != null)
      node match {
        case DefaultPat() =>
          return node.and.bodyToTree();
        case _ =>
      }
      patNode = patNode.nextH();
    }
    otherwise;
  }

  /** This method translates pattern matching expressions that match
   *  on integers on the top level.
   */
  def intSwitchToTree(): Tree = {
  def insert1(tag: Int, body: Tree, current: TagBodyPair): TagBodyPair = {
    if (current == null)
      return new TagBodyPair(tag, body, null);
    else if (tag > current.tag)
      return new TagBodyPair(current.tag, current.body, insert1(tag, body, current.next));
    else
      return new TagBodyPair(tag, body, current);
  }

    //print();
    val ncases = numCases(root.and);
    val matchError = ThrowMatchError(selector.pos);
    // without a case, we return a match error if there is no default case
    if (ncases == 0)
      return defaultBody(root.and, matchError);
    // for one case we use a normal if-then-else instruction
    else if (ncases == 1) {
      root.and.or match {
        case ConstantPat(value) =>
          return If(Equals(selector, Literal(value)),
                    (root.and.or.and).bodyToTree(),
                    defaultBody(root.and, matchError));
        case _ =>
          return generalSwitchToTree();
      }
    }
    //
    // if we have more than 2 cases than use a switch statement
    val _h:Header = root.and.asInstanceOf[Header];

    val next = _h.next;
    var mappings: TagBodyPair = null;
    var defaultBody1: Tree = matchError;
    var patNode = root.and;
    while (patNode != null) {
      var node = patNode.or;
      while (node != null) {
        node match {
          case DefaultPat() =>
            if (defaultBody1 != null)
              scala.Predef.error("not your day today");
            defaultBody1 = node.and.bodyToTree();
            node = node.or;

          case ConstantPat( value: Int )=>
            mappings = insert1(
              value,
              node.and.bodyToTree(),
              mappings);
          node = node.or;
        }
      }
      patNode = patNode.nextH();
    }

    var n = mappings.length();
    var nCases: List[CaseDef] = Nil;
    while (mappings != null) {
      nCases = CaseDef(Literal(mappings.tag),
                       mappings.body) :: nCases;
      mappings = mappings.next;
    }
    /*
    val tags = new Array[Int](n);
    val bodies = new Array[Tree](n);
    n = 0;
    while (mappings != null) {
      tags(n) = mappings.tag;
      bodies(n) = mappings.body;
      n = n + 1;
      mappings = mappings.next;
    }
    return Switch(selector, tags, bodies, defaultBody1, resultVar.tpe);
    */
    nCases = CaseDef(Ident(nme.WILDCARD), defaultBody1) :: nCases;
    return Match(selector, nCases)
  }

  def generalSwitchToTree(): Tree = {
    val ts = List(
      ValDef(root.symbol, selector),
      ValDef(resultVar, EmptyTree /* DefaultValue */));
    val res = If(toTree(root.and),
                 Ident(resultVar),
                 ThrowMatchError(selector.pos /* ,
                                    Ident(root.symbol) */));
    return Block(ts, res);
  }

  /*protected*/ def toTree(node1: PatternNode): Tree = {
    def optimize1(selType:Type, alternatives1: PatternNode ): Boolean = {
      var alts = alternatives1;
      if (!optimize || !isSubType(selType, defs.ScalaObjectClass.info))
        return false;
      var cases = 0;
      while (alts != null) {
        alts match {
          case ConstrPat(_) =>
            if (alts.getTpe().symbol.hasFlag(Flags.CASE))
              cases = cases +1;
            else
              return false;

          case DefaultPat() =>
            ;
          case _ =>
            return false;
        }
        alts = alts.or;
      }
      return cases > 2;
    } // def optimize

    var node = node1;
    var res: Tree = Literal(false);
    while (node != null)
    node match {
      case _h:Header =>
        val selector = _h.selector;
      val next = _h.next;
      //res = And(mkNegate(res), toTree(node.or, selector));
      //Console.println("HEADER TYPE = " + selector.type);
      if (optimize1(node.getTpe(), node.or))
        res = Or(res, toOptTree(node.or, selector));
      else
            res = Or(res, toTree(node.or, selector));
          node = next;

        case _b:Body =>
          var bound = _b.bound;
          val guard = _b.guard;
          val body  = _b.body;
          if ((bound.length == 0) &&
              (guard.length == 0) &&
              (body.length == 0)) {
                return Literal(true);
              } else if (!doBinding)
                bound = Predef.Array[Array[ValDef]]( Predef.Array[ValDef]() );
                var i = guard.length - 1; while(i >= 0) {
                  val ts:Seq[Tree] = bound(i).asInstanceOf[Array[Tree]];
                  var res0: Tree =
                    Block(
                      List(Assign(Ident(resultVar), body(i))),
                      Literal(true));
                  if (guard(i) != EmptyTree)
                    res0 = And(guard(i), res0);
                  res = Or(Block(ts.toList, res0), res);
                  i = i - 1
                }
        return res;
        case _ =>
          scala.Predef.error("I am tired");
      }
      return res;
    }


    class TagNodePair(tag1: int, node1: PatternNode, next1: TagNodePair) {
      var tag: int = tag1;
      var node: PatternNode = node1;
      var next: TagNodePair = next1;

      def  length(): Int = {
        return if (null == next)  1 else (next.length() + 1);
      }
    }

    protected def toOptTree(node1: PatternNode, selector: Tree): Tree = {
      def insert2(tag: Int, node: PatternNode, current: TagNodePair): TagNodePair = {
        if (current == null)
          return new TagNodePair(tag, node, null);
        else if (tag > current.tag)
          return new TagNodePair(current.tag, current.node, insert2(tag, node, current.next));
        else if (tag == current.tag) {
          val old = current.node;
          ({current.node = node; node}).or = old;
          return current;
        } else
          return new TagNodePair(tag, node, current);
      }

      def  insertNode(tag:int , node:PatternNode , current:TagNodePair ): TagNodePair = {
        val newnode = node.dup();
        newnode.or = null;
        return insert2(tag, newnode, current);
      }
      var node = node1;
      //System.err.println("pm.toOptTree called"+node);
      var cases: TagNodePair  = null;
      var defaultCase: PatternNode  = null;
      while (node != null)
      node match {
        case ConstrPat(casted) =>
          cases = insertNode(node.getTpe().symbol.tag, node, cases);
          node = node.or;

        case DefaultPat() =>
          defaultCase = node;
          node = node.or;

        case _ =>
          scala.Predef.error("errare humanum est");
      }
      var n = cases.length();
      /*
      val tags = new Array[int](n);
      val bodies = new Array[Tree](n);
      n = 0;
      while (null != cases) {
        tags(n) = cases.tag;
        bodies(n) = toTree(cases.node, selector);
        n = n + 1;
        cases = cases.next;
      }
      */


      /*
      return
      Switch(
        Apply(
          Select(selector.duplicate, defs.ScalaObjectClass_tag),
          List()),
        tags,
        bodies,
        { if (defaultCase == null) Literal(false) else toTree(defaultCase.and) },
                        defs.boolean_TYPE());
                        */
      var nCases: List[CaseDef] = Nil;
      while (cases != null) {
        nCases = CaseDef(Literal(cases.tag),
                         toTree(cases.node, selector)) :: nCases;
        cases = cases.next;
      }

      val defBody = if (defaultCase == null)
                      Literal(false)
                    else
                      toTree(defaultCase.and);

      nCases = CaseDef(Ident(nme.WILDCARD), defBody) :: nCases;
      return Match(Apply(Select(selector.duplicate, defs.ScalaObjectClass_tag),
                         List()),
                   nCases);
    }

    protected def toTree(node:PatternNode , selector:Tree ): Tree = {
      //System.err.println("pm.toTree("+node+","+selector+")");
      if (node == null)
        return Literal(false);
      else
        node match {
          case DefaultPat() =>
            return toTree(node.and);

          case ConstrPat(casted) =>
            return If(gen.mkIsInstanceOf(selector.duplicate, node.getTpe()),
                      Block(
                        List(ValDef(casted,
                                    gen.mkAsInstanceOf(selector.duplicate, node.getTpe(), true))),
                            toTree(node.and)),
                      toTree(node.or, selector.duplicate));
          case SequencePat(casted, len) =>
            return
          Or(
            And(
              And(gen.mkIsInstanceOf(selector.duplicate, node.getTpe()),
                     Equals(
                       Apply(
                         Select(
                           gen.mkAsInstanceOf(selector.duplicate,
                                              node.getTpe(),
                                              true),
                           defs.Seq_length),
                         List()),
                       Literal(len))),
              Block(
                List(
                  ValDef(casted,
                         gen.mkAsInstanceOf(selector.duplicate, node.getTpe(), true))),
                  toTree(node.and))),
            toTree(node.or, selector.duplicate));
          case ConstantPat(value) =>
            return If(Equals(selector.duplicate,
                                Literal(value)),
                      toTree(node.and),
                      toTree(node.or, selector.duplicate));
          case VariablePat(tree) =>
            return If(Equals(selector.duplicate, tree),
                      toTree(node.and),
                      toTree(node.or, selector.duplicate));
          case AltPat(header) =>
            return If(toTree(header),
                      toTree(node.and),
                      toTree(node.or, selector.duplicate));
          case _ =>
            scala.Predef.error("can't plant this tree");
        }
    }
}


}
