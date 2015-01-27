package org.nlogo.extensions.nw.prim

import java.awt.Color
import java.io.{File, FileReader, IOException}

import org.gephi.data.attributes.`type`.DynamicType
import org.gephi.data.attributes.api.{AttributeColumn, AttributeType, AttributeController, AttributeRow}
import org.gephi.graph.api.GraphController
import org.gephi.io.exporter.api.ExportController
import org.gephi.io.importer.api.EdgeDraft.EdgeType
import org.gephi.io.importer.api.{EdgeDefault, EdgeDraftGetter, ImportController, NodeDraftGetter}
import org.gephi.io.importer.spi.FileImporter
import org.gephi.project.api.ProjectController
import org.nlogo.agent.Agent
import org.nlogo.agent.AgentSet
import org.nlogo.agent.Link
import org.nlogo.agent.Turtle
import org.nlogo.agent.World
import org.nlogo.api
import org.nlogo.api._
import org.nlogo.api.Syntax._
import org.nlogo.extensions.nw.GraphContextProvider
import org.nlogo.extensions.nw.NetworkExtensionUtil._
import org.nlogo.extensions.nw.gephi.GephiUtils
import org.nlogo.nvm.ExtensionContext
import org.openide.util.Lookup

import scala.collection.JavaConverters._
import scala.util.control.Exception.allCatch

class Load extends TurtleAskingCommand {

  override def getSyntax = commandSyntax(Array(StringType, TurtlesetType, LinksetType, CommandBlockType | OptionalType))
  override def perform(args: Array[api.Argument], context: api.Context) = GephiUtils.withNWLoaderContext {
    val ws = context.asInstanceOf[ExtensionContext].workspace
    val turtleBreed = args(1).getAgentSet.requireTurtleBreed
    val linkBreed = args(2).getAgentSet.requireLinkBreed
    val file = new File(ws.fileManager.attachPrefix(args(0).getString))
    GephiIO.load(file, ws.world, turtleBreed, linkBreed, askTurtles(context))
  }
}

class LoadFileType(extension: String) extends TurtleAskingCommand {
  override def getSyntax = commandSyntax(Array(StringType, TurtlesetType, LinksetType, CommandBlockType | OptionalType))
  override def perform(args: Array[api.Argument], context: api.Context) = GephiUtils.withNWLoaderContext {
    val ws = context.asInstanceOf[ExtensionContext].workspace
    val turtleBreed = args(1).getAgentSet.requireTurtleBreed
    val linkBreed = args(2).getAgentSet.requireLinkBreed
    val file = new File(ws.fileManager.attachPrefix(args(0).getString))
    GephiIO.load(file, ws.world, turtleBreed, linkBreed, askTurtles(context) _, extension)
  }
}

class LoadFileTypeDefaultBreeds(extension: String) extends TurtleAskingCommand {
  override def getSyntax = commandSyntax(Array(StringType, CommandBlockType | OptionalType))
  override def perform(args: Array[api.Argument], context: api.Context) = GephiUtils.withNWLoaderContext {
    val ws = context.asInstanceOf[ExtensionContext].workspace
    val file = new File(ws.fileManager.attachPrefix(args(0).getString))
    GephiIO.load(file, ws.world, ws.world.turtles, ws.world.links, askTurtles(context) _, extension)
  }
}

class Save(gcp: GraphContextProvider) extends api.DefaultCommand {
  private type JDouble = java.lang.Double
  private type JBoolean = java.lang.Boolean
  private type JNumber = java.lang.Number
  override def getSyntax = commandSyntax(Array(StringType))
  override def perform(args: Array[api.Argument], context: api.Context) = GephiUtils.withNWLoaderContext {
    val world = context.getAgent.world.asInstanceOf[World]
    val program = world.program
    val workspace = context.asInstanceOf[ExtensionContext].workspace
    val fm = context.asInstanceOf[org.nlogo.nvm.ExtensionContext].workspace.fileManager
    val fn = fm.attachPrefix(args(0).getString)
    val pc = Lookup.getDefault().lookup(classOf[ProjectController])
    val exportController = Lookup.getDefault.lookup(classOf[ExportController])
    pc.newProject()
    val ws = pc.getCurrentWorkspace
    val gm = Lookup.getDefault.lookup(classOf[GraphController]).getModel
    val am = Lookup.getDefault.lookup(classOf[AttributeController]).getModel
    val gc = gcp.getGraphContext(world)
    val graph = (gc.links.exists(_.isDirectedLink), gc.links.exists(!_.isDirectedLink)) match {
      case (true, true)  => gm.getMixedGraph
      case (true, false) => gm.getDirectedGraph
      case (false, true) => gm.getUndirectedGraph
      case _             => gm.getGraph
    }

    val nodeAttributes = am.getNodeTable

    val turtlesOwnAttributes: Map[String, AttributeColumn] = program.turtlesOwn.asScala.map { name =>
      val kind = getBestType(world.turtles.asIterable[Turtle].map(t => t.getTurtleOrLinkVariable(name)))
      name -> Option(nodeAttributes.getColumn(name)).getOrElse(nodeAttributes.addColumn(name, kind))
    }.toMap

    val breedsOwnAttributes: Map[AgentSet, Map[String, AttributeColumn]] = program.breeds.asScala.collect {
      case (breedName, breed: AgentSet) => breed -> program.breedsOwn.get(breedName).asScala.map { name =>
        val kind = getBestType(breed.asIterable[Turtle].map(t => t.getBreedVariable(name)))
        name -> Option(nodeAttributes.getColumn(name)).getOrElse(nodeAttributes.addColumn(name, kind))
      }.toMap
    }.toMap

    val nodes = gc.turtles.map { turtle =>
      val node = gm.factory.newNode(turtle.toString)
      turtlesOwnAttributes.foreach { case (name, col) =>
          node.getAttributes.setValue(col.getIndex, coerce(turtle.getTurtleOrLinkVariable(name), col.getType))
      }
      if (turtle.getBreed != world.turtles) {
        breedsOwnAttributes(turtle.getBreed).foreach { case (name, col) =>
          node.getAttributes.setValue(col.getIndex, coerce(turtle.getBreedVariable(name), col.getType))
        }
      }
      val color = api.Color.getColor(turtle.color())
      node.getNodeData.setColor(color.getRed / 255f, color.getGreen / 255f, color.getBlue / 255f)
      node.getNodeData.setAlpha(color.getAlpha / 255f)
      graph.addNode(node)
      turtle -> node
    }.toMap

    gc.links.foreach { link =>
      val edge = gm.factory.newEdge(nodes(link.end1), nodes(link.end2), 1, link.isDirectedLink)
      graph.addEdge(edge)
    }
    ws.add(gm)
    ws.add(am)
    exportController.exportFile(new File(fn))
  }

  private def getBestType(values: Iterable[AnyRef]): AttributeType = {
    if (values.forall(_.isInstanceOf[JNumber])){
      AttributeType.DOUBLE
    } else if (values.forall(_.isInstanceOf[JBoolean])) {
      AttributeType.BOOLEAN
    } else {
      AttributeType.STRING
    }
  }

  private def coerce(value: AnyRef, kind: AttributeType): AnyRef = value -> kind match {
    case (x: JNumber, AttributeType.DOUBLE) => x.doubleValue: JDouble
    case (b: JBoolean, AttributeType.BOOLEAN) => b
    // For Strings, we want to keep the escaping but ditch the surrounding quotes. BCH 1/26/2015
    case (s: String, AttributeType.STRING) => Dump.logoObject(s, readable = true, exporting = false).drop(1).dropRight(1)
    case (s: AnyRef, AttributeType.STRING) => Dump.logoObject(s, readable = true, exporting = false)
  }
}

object GephiIO{
  val importController = GephiUtils.withNWLoaderContext {
    Lookup.getDefault.lookup(classOf[ImportController])
  }

  def load(file: File, world: World,
           defaultTurtleBreed: AgentSet, defaultLinkBreed: AgentSet,
           initTurtles: TraversableOnce[Turtle] => Unit): Unit = GephiUtils.withNWLoaderContext {
    load(file, world, defaultTurtleBreed, defaultLinkBreed, initTurtles, importController.getFileImporter(file))
  }

  def load(file: File, world: World,
           defaultTurtleBreed: AgentSet, defaultLinkBreed: AgentSet,
           initTurtles: TraversableOnce[Turtle] => Unit,
           extension: String): Unit = GephiUtils.withNWLoaderContext {
    load(file, world, defaultTurtleBreed, defaultLinkBreed, initTurtles, importController.getFileImporter(extension))
  }

  def load(file: File, world: World,
           defaultTurtleBreed: AgentSet, defaultLinkBreed: AgentSet,
           initTurtles: TraversableOnce[Turtle] => Unit,
           importer: FileImporter): Unit = GephiUtils.withNWLoaderContext {
    val turtleBreeds = world.program.breeds.asScala.toMap
    val linkBreeds = world.program.linkBreeds.asScala.toMap

    val container = try using(new FileReader(file))(r => importController.importFile(r, importer))
                    catch { case e: IOException => throw new ExtensionException(e) }
    val unloader = container.getUnloader
    val defaultDirected = unloader.getEdgeDefault == EdgeDefault.DIRECTED
    val defaultUndirected = unloader.getEdgeDefault == EdgeDefault.UNDIRECTED
    val nodes: Iterable[NodeDraftGetter] = unloader.getNodes.asScala
    val edges: Iterable[EdgeDraftGetter] = unloader.getEdges.asScala

    val nodeToTurtle: Map[NodeDraftGetter, Turtle] = nodes zip nodes.map {
      node => {
        val attrs = getAttributes(node.getAttributeRow) ++
                    pair("LABEL", node.getLabel) ++
                    pair("LABEL-COLOR", node.getLabelColor) ++
                    pair("COLOR", node.getColor)
        // Note that node's have a getSize. This does not correspond to the `size` attribute in files so should not be
        // used. BCH 1/21/2015

        val breed = getBreed(attrs, turtleBreeds).getOrElse(defaultTurtleBreed)
        val turtle = createTurtle(breed, world.mainRNG)
        (attrs - "BREED") foreach (setAttribute(world, turtle) _).tupled
        turtle
      }
    } toMap

    val badEdges: TraversableOnce[EdgeDraftGetter] = edges.map { edge =>
      val source = nodeToTurtle(edge.getSource)
      val target = nodeToTurtle(edge.getTarget)
      // There are three gephi edge types: directed, undirected, and mutual. Mutual is pretty much just indicating that
      // and edge goes both ways/there are two edges in either direction, so we treat it as either. BCH 1/22/2015
      val attrs = getAttributes(edge.getAttributeRow) ++
                  pair("LABEL", edge.getLabel) ++
                  pair("LABEL-COLOR", edge.getLabelColor) ++
                  pair("COLOR", edge.getColor) ++
                  pair("WEIGHT", edge.getWeight.toDouble: java.lang.Double)

      val breed = getBreed(attrs, linkBreeds).getOrElse(defaultLinkBreed)


      val gephiDirected = edge.getType == EdgeType.DIRECTED
      val gephiUndirected = edge.getType == EdgeType.UNDIRECTED
      val bad = if (breed.isDirected == breed.isUndirected) {
        // This happens when the directedness of the default breed hasn't been set yet
        if (edge.getType == null) breed.setDirected(defaultDirected)
        else breed.setDirected(gephiDirected || edge.getType == EdgeType.MUTUAL)
        false
      } else {
        (breed.isDirected && gephiUndirected) || (breed.isUndirected && gephiDirected)
      }

      val links = List(world.linkManager.createLink(source, target, breed)) ++ {
        if (breed.isDirected && edge.getType == EdgeType.MUTUAL)
          Some(world.linkManager.createLink(target, source, breed))
        else
          None
      }
      links foreach { l => (attrs - "BREED") foreach (setAttribute(world, l) _).tupled }

      if (bad) Some(edge)
      else None
    }.collect{case Some(e: EdgeDraftGetter) => e}

    initTurtles(nodeToTurtle.values)

    if(badEdges.nonEmpty) {
      val edgesList = badEdges.map(e => e.getSource.getId + "->" + e.getTarget.getId).mkString(", ")
      val errorMsg =
        "The following edges had a directedness different than their assigned breed. They have been given " +
        "the directedness of their breed. If you wish to ignore this error, wrap this command in a CAREFULLY:"
      throw new ExtensionException(errorMsg + " " + edgesList)
    }
  }

  private type JDouble = java.lang.Double
  private type JBoolean = java.lang.Boolean
  private type JNumber = java.lang.Number
  private def convertColor(c: Color): LogoList = {
    val l = LogoList(c.getRed.toDouble: JDouble,
                     c.getGreen.toDouble: JDouble,
                     c.getBlue.toDouble: JDouble)
    if (c.getAlpha != 255) l.lput(c.getAlpha.toDouble: JDouble)
    l
  }

  private def pair(key: String, value: AnyRef): Option[(String, AnyRef)] =
    Option(value) map (v => key -> convertAttribute(key, v))

  private def getAttributes(row: AttributeRow): Map[String, AnyRef] =
    row.getValues.filter(v => v.getValue != null).map { v =>
      v.getColumn.getTitle.toUpperCase -> convertAttribute(v.getColumn.getTitle.toUpperCase, v.getValue)
    }.toMap

  private def getBreed(attributes: Map[String, AnyRef], breeds: Map[String, AnyRef]): Option[AgentSet] =
    attributes.get("BREED").collect{case s: String => s.toUpperCase}
                          .flatMap(s => breeds.get(s))
                          .collect{case b: AgentSet => b}

  private val doubleBuiltins = Set("XCOR", "YCOR", "HEADING", "PEN-SIZE", "THICKNESS", "SIZE")
  private val booleanBuiltins = Set("HIDDEN")
  private val colorBuiltins = Set("COLOR", "LABEL-COLOR")
  private def convertAttribute(name: String, o: Any): AnyRef = {
    if (doubleBuiltins.contains(name)) o match {
      case x: JNumber => x.doubleValue: JDouble
      case s: String  => allCatch.opt(s.toDouble: JDouble).getOrElse(0.0: JDouble)
      case _ => "" // purposely invalid so it won't set. Might want to throw error instead. BCH 1/25/2015
    } else if (booleanBuiltins.contains(name)) o match {
      case x: JNumber  => x.doubleValue != 0: JBoolean
      case b: JBoolean => b
      case s: String   => allCatch.opt(s.toBoolean: JBoolean).getOrElse(false: JBoolean)
      case _ => "" // purposely invalid so it won't set. Might want to throw error instead. BCH 1/25/2015
    } else if (colorBuiltins.contains(name)) o match {
      case c: Color => convertColor(c)
      case x: JNumber => x.doubleValue: JDouble
      case c: java.util.Collection[_] => LogoList.fromIterator(c.asScala.map(convertAttribute _).iterator)
      case s: String  => allCatch.opt(s.toDouble: JDouble).getOrElse("")
      case _ => "" // purposely invalid so it won't set. Might want to throw error instead. BCH 1/25/2015
    } else {
      convertAttribute(o)
    }
  }

  private def convertAttribute(o: Any): AnyRef = o match {
    case c: Color => convertColor(c)
    case n: JNumber => n.doubleValue: JDouble
    case b: JBoolean => b
    case c: java.util.Collection[_] => LogoList.fromIterator(c.asScala.map(x => convertAttribute(x)).iterator)
    // There may be a better handling of dynamic values, but this seems good enough for now. BCH 1/21/2015
    case d: DynamicType[_] => LogoList.fromIterator(d.getValues.asScala.map(x => convertAttribute(x)).iterator)
    case a: Array[_] => LogoList.fromIterator(a.map(convertAttribute _).iterator)
    // Gephi attributes are strongly typed. Many formats, however, are not, and neither is NetLogo. Thus, when we use
    // String as a kind of AnyRef. This is a bad solution, but better than alternatives. BCH 1/25/2015
    case x => x.toString
  }

  private def setAttribute(world: World, agent: Agent)(name: String, value: AnyRef): Unit = {
    val i = world.indexOfVariable(agent, name)
    if (i != -1) try { agent.setVariable(i, value) } catch { case e: AgentException => /*Invalid variable or value, so skip*/}
  }
}

