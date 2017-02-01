package org.dbpedia.extraction.nif

import java.net.URI

import org.apache.commons.lang3.StringEscapeUtils
import org.dbpedia.extraction.config.provenance.DBpediaDatasets
import org.dbpedia.extraction.mappings.RecordSeverity
import org.dbpedia.extraction.nif.LinkExtractor.NifExtractorContext
import org.dbpedia.extraction.ontology.RdfNamespace
import org.dbpedia.extraction.transform.{Quad, QuadBuilder}
import org.dbpedia.extraction.util.{Config, CssConfigurationMap, UriUtils, WikiUtil}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element, TextNode}
import org.jsoup.parser.Tag
import org.jsoup.select.NodeTraversor

import scala.collection.convert.decorateAsScala._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}
/**
  * Created by Chile on 1/19/2017.
  */
abstract class HtmlNifExtractor(nifContextIri: String, language: String, configFile : Config) {

  assert(nifContextIri.contains("?"), "the nifContextIri needs a query part!")

  protected val writeLinkAnchors = configFile.nifParameters.writeLinkAnchor
  protected val writeStrings = configFile.nifParameters.writeAnchor
  protected val cssSelectorConfigMap = new CssConfigurationMap(configFile.nifParameters.cssSelectorMap).getCssSelectors(language)

  protected lazy val nifContext = QuadBuilder.dynamicPredicate(language, DBpediaDatasets.NifContext.encoded) _
  protected lazy val nifStructure = QuadBuilder.dynamicPredicate(language, DBpediaDatasets.NifPageStructure.encoded) _
  protected lazy val nifLinks = QuadBuilder.dynamicPredicate(language, DBpediaDatasets.NifTextLinks.encoded) _
  protected lazy val rawTables = QuadBuilder.dynamicPredicate(language, DBpediaDatasets.RawTables.encoded) _

  protected val templateString = "Template"

  private val sectionMap = new mutable.HashMap[PageSection, ExtractedSection]()

  /**
    * Extract the relevant html page divided in sections and paragraphs
    * @param html - the html string downloaded from the destination resource
    * @return -  a collection of section objects
    */
  def getRelevantParagraphs (html: String): Seq[PageSection]

  /**
    *
    * @param graphIri
    * @param subjectIri
    * @param html
    * @param exceptionHandle
    * @return
    */
  def extractNif(graphIri: String, subjectIri: String, html: String)(exceptionHandle: (String, RecordSeverity.Value, Throwable) => Unit): Seq[Quad] = {

    val sections = getRelevantParagraphs(html)

    var context = ""
    var offset = 0
    val quads = for(section <- sections) yield {
      extractTextFromHtml(section, new NifExtractorContext(language, subjectIri, templateString)) match {
        case Success(extractionResults) => {
          sectionMap.put(section, extractionResults)
          sectionMap.put(extractionResults, extractionResults)

          if (context.length != 0) {
            context = context + "\n\n"
            offset += 2
          }
          var quad = makeStructureElements(extractionResults, nifContextIri, graphIri, offset)

          val extractedText = calculateText(extractionResults.paragraphs)
          offset += extractedText._2
          context += extractedText._1

          //collect additional triples
          quad ++= extendSectionTriples(extractionResults, graphIri, subjectIri)
          //forward exceptions
          extractionResults.errors.foreach(exceptionHandle(_, RecordSeverity.Warning, null))
          quad
        }
        case Failure(e) => {
          exceptionHandle(e.getMessage, RecordSeverity.Exception, e)
          List()
        }
      }
    }

    quads.flatten ++ makeContext(context, nifContextIri, graphIri, offset) ++ extendContextTriples(quads.flatten, graphIri, subjectIri)
  }

  /**
    * Each extracted section can be further enriched by this function, by providing additional quads
    * @param extractionResults - the extraction results for a particular section
    * @return
    */
  def extendSectionTriples(extractionResults: ExtractedSection, graphIri: String, subjectIri: String): Seq[Quad]

  /**
    * For each page additional triples can be added with this function
    * @param quads - the current collection of quads
    * @return
    */
  def extendContextTriples(quads: Seq[Quad], graphIri: String, subjectIri: String): Seq[Quad]

  private def makeContext(text: String, contextUri: String, sourceUrl: String, contextEnd: Int): ListBuffer[Quad] = {
    var cont = ListBuffer[Quad]()
    val wikipediaUrl = if(sourceUrl.contains("?")) sourceUrl.substring(0, sourceUrl.indexOf('?')) else sourceUrl
    if (contextEnd == 0)
      return ListBuffer()
    cont += nifContext(contextUri, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append("Context"), sourceUrl, null)
    cont += nifContext(contextUri, RdfNamespace.NIF.append("beginIndex"), "0", sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger") )
    cont += nifContext(contextUri, RdfNamespace.NIF.append("endIndex"), contextEnd.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger") )
    cont += nifContext(contextUri, RdfNamespace.NIF.append("sourceUrl"), wikipediaUrl, sourceUrl, null)
    cont += nifContext(contextUri, RdfNamespace.NIF.append("isString"), text, sourceUrl, RdfNamespace.XSD.append("string"))
    cont
  }

  private def makeStructureElements(section: ExtractedSection, contextUri: String, sourceUrl: String, offset: Int): ListBuffer[Quad] = {
    var triples = ListBuffer[Quad]()
    var off = offset
    val sectionUri = section.getSectionIri(offset)
    triples += nifStructure(sectionUri, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append("Section"), sourceUrl, null)
    triples += nifStructure(sectionUri, RdfNamespace.SKOS.append("notation"), section.ref, sourceUrl, RdfNamespace.RDFS.append("Literal"))
    triples += nifStructure(sectionUri, RdfNamespace.NIF.append("beginIndex"), offset.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
    triples += nifStructure(sectionUri, RdfNamespace.NIF.append("endIndex"), (offset + calculateText(section.paragraphs)._2).toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
    triples += nifStructure(sectionUri, RdfNamespace.NIF.append("referenceContext"), contextUri, sourceUrl, null)

    //adding navigational properties
    section.getPrev match{
      case Some(p) => {
        triples += nifStructure(sectionUri, RdfNamespace.NIF.append("previousSection"), p.getSectionIri(), sourceUrl, null)
        triples += nifStructure(p.getSectionIri(), RdfNamespace.NIF.append("nextSection"), sectionUri, sourceUrl, null)
      }
      case None =>
    }
    section.getTop match{
      case Some(p) => {
        triples += nifStructure(sectionUri, RdfNamespace.NIF.append("superString"), p.getSectionIri(), sourceUrl, null)
        triples += nifStructure(p.getSectionIri(), RdfNamespace.NIF.append("hasSection"), sectionUri, sourceUrl, null)
      }
      case None =>
    }

    if(section.top.isEmpty) {
      triples += nifStructure(sectionUri, RdfNamespace.NIF.append("superString"), contextUri, sourceUrl, null)
      triples += nifStructure(contextUri, RdfNamespace.NIF.append("hasSection"), sectionUri, sourceUrl, null)
      if (section.prev.isEmpty)
        triples += nifStructure(contextUri, RdfNamespace.NIF.append("firstSection"), sectionUri, sourceUrl, null)
      if (section.next.isEmpty)
        triples += nifStructure(contextUri, RdfNamespace.NIF.append("lastSection"), sectionUri, sourceUrl, null)
    }
    else{
      triples += nifStructure(sectionUri, RdfNamespace.NIF.append("superString"), section.getTop.get.getSectionIri(), sourceUrl, null)
      triples += nifStructure(section.getTop.get.getSectionIri(), RdfNamespace.NIF.append("hasSection"), sectionUri, sourceUrl, null)
      if (section.prev.isEmpty)
        triples += nifStructure(section.getTop.get.getSectionIri(), RdfNamespace.NIF.append("firstSection"), sectionUri, sourceUrl, null)
      if (section.next.isEmpty)
        triples += nifStructure(section.getTop.get.getSectionIri(), RdfNamespace.NIF.append("lastSection"), sectionUri, sourceUrl, null)
    }

    //further specifying paragraphs of every section
    var lastParagraph: String = null

    for(i <- section.paragraphs.indices) {
      if(section.paragraphs(i).getTagName.matches("h\\d")) {
        //titles were extracted as paragraphs -> create title
        triples ++= createSectionTitle(section, contextUri, sourceUrl, offset)
        off += Paragraph.GetEscapedStringLength(section.title) + 1
      }
      else
        writeParagraph(i)
    }


    def writeParagraph(i: Int): Unit = {
      //add raw tables (text length might be 0)
      triples ++= saveRawTables(section.paragraphs(i).getTableHtml.asScala, section, contextUri, sourceUrl, off)

      if(section.paragraphs(i).getLength == 0)
        return

      val paragraph = getNifIri("paragraph", section.paragraphs(i).getBegin(off), section.paragraphs(i).getEnd(off))

      if (lastParagraph != null) //provide the nextParagraph triple
        triples += nifStructure(lastParagraph, RdfNamespace.NIF.append("nextParagraph"), paragraph, sourceUrl, null)

      triples += nifStructure(paragraph, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append("Paragraph"), sourceUrl, null)
      triples += nifStructure(paragraph, RdfNamespace.NIF.append("beginIndex"), off.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
      triples += nifStructure(paragraph, RdfNamespace.NIF.append("endIndex"), section.paragraphs(i).getEnd(off).toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
      triples += nifStructure(paragraph, RdfNamespace.NIF.append("referenceContext"), contextUri, sourceUrl, null)
      triples += nifStructure(paragraph, RdfNamespace.NIF.append("superString"), sectionUri, sourceUrl, null)
      if (writeStrings)
        triples += nifStructure(paragraph, RdfNamespace.NIF.append("anchorOf"), section.paragraphs(i).getText, sourceUrl, RdfNamespace.XSD.append("string"))
      triples += nifStructure(sectionUri, RdfNamespace.NIF.append("hasParagraph"), paragraph, sourceUrl, null)
      if (i == 0)
        triples += nifStructure(sectionUri, RdfNamespace.NIF.append("firstParagraph"), paragraph, sourceUrl, null)
      if (i == section.paragraphs.indices.last)
        triples += nifStructure(sectionUri, RdfNamespace.NIF.append("lastParagraph"), paragraph, sourceUrl, null)

      lastParagraph = paragraph
      triples ++= makeWordsFromLinks(section.paragraphs(i).getLinks.asScala.toList, contextUri, paragraph, sourceUrl, off)

      off += section.paragraphs(i).getLength + (if(Paragraph.FollowedByWhiteSpace(section.paragraphs(i).getText)) 1 else 0)
    }

    triples
  }

  private def saveRawTables(tables: mutable.Map[Integer, String], section: PageSection, contextUri: String, sourceUrl: String, offset: Int): ListBuffer[Quad] = {
    val triples = ListBuffer[Quad]()
    for(table <- tables.toList){
      section.tableCount = section.tableCount+1
      val position = offset + table._1
      val tableUri = getNifIri("table", position, position).replaceFirst("&char=.*", "&ref=" + section.ref + "_" + section.tableCount)

      triples += rawTables(tableUri, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append("Structure"), sourceUrl, null)
      triples += rawTables(tableUri, RdfNamespace.NIF.append("referenceContext"), contextUri, sourceUrl, null)
      triples += rawTables(tableUri, RdfNamespace.NIF.append("beginIndex"), position.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
      triples += rawTables(tableUri, RdfNamespace.NIF.append("endIndex"), position.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
      triples += rawTables(tableUri, RdfNamespace.DC.append("source"), table._2, sourceUrl, RdfNamespace.RDF.append("XMLLiteral"))
    }
    triples
  }

  private def createSectionTitle(section: ExtractedSection, contextUri: String, sourceUrl: String, offset: Int): ListBuffer[Quad] = {
    val triples = ListBuffer[Quad]()
    if(section.title == "abstract")
      return triples                //the abstract has no title

    val tableUri = getNifIri("title", offset, offset + section.title.length)
    section.tableCount = section.tableCount+1

    triples += nifStructure(tableUri, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append("Title"), sourceUrl, null)
    triples += nifStructure(tableUri, RdfNamespace.NIF.append("referenceContext"), contextUri, sourceUrl, null)
    triples += nifStructure(tableUri, RdfNamespace.NIF.append("beginIndex"), offset.toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
    triples += nifStructure(tableUri, RdfNamespace.NIF.append("endIndex"), (offset + section.title.length).toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
    triples += nifStructure(tableUri, RdfNamespace.NIF.append("superString"), section.getSectionIri(), sourceUrl, null)
    if(writeLinkAnchors)
      triples += nifLinks(tableUri, RdfNamespace.NIF.append("anchorOf"), section.title, sourceUrl, RdfNamespace.XSD.append("string"))
    triples
  }

  private def makeWordsFromLinks(links: List[Link], contextUri: String, paragraphUri: String, sourceUrl: String, offset: Int): ListBuffer[Quad] = {
    var words = ListBuffer[Quad]()
    for (link <- links) {
      if (link.getWordEnd - link.getWordStart > 0) {
        val typ = if (link.getLinkText.split(" ").length > 1) "Phrase" else "Word"
        val word = getNifIri(typ.toString.toLowerCase, offset + link.getWordStart, offset + link.getWordEnd)
        words += nifLinks(word, RdfNamespace.RDF.append("type"), RdfNamespace.NIF.append(typ), sourceUrl, null)
        words += nifLinks(word, RdfNamespace.NIF.append("referenceContext"), contextUri, sourceUrl, null)
        words += nifLinks(word, RdfNamespace.NIF.append("beginIndex"), (offset + link.getWordStart).toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
        words += nifLinks(word, RdfNamespace.NIF.append("endIndex"), (offset + link.getWordEnd).toString, sourceUrl, RdfNamespace.XSD.append("nonNegativeInteger"))
        words += nifLinks(word, RdfNamespace.NIF.append("superString"), paragraphUri, sourceUrl, null)
        UriUtils.createUri(link.getUri) match{
          case Success(s) => words += nifLinks(word, "http://www.w3.org/2005/11/its/rdf#taIdentRef", s.toString, sourceUrl, null)  //TODO IRI's might throw exception in org.dbpedia.extraction.destinations.formatters please check this
          case Failure(f) =>
        }
        if(writeLinkAnchors)
          words += nifLinks(word, RdfNamespace.NIF.append("anchorOf"), link.getLinkText, sourceUrl, RdfNamespace.XSD.append("string"))
      }
    }
    words
  }

  protected def extractTextFromHtml(pageSection: PageSection, extractionContext: NifExtractorContext): Try[ExtractedSection] = {
    Try {
      val section = new ExtractedSection(pageSection, List(), List())
      val element = new Element(Tag.valueOf("div"), "")
      pageSection.content.foreach(element.appendChild)

        if (element.isInstanceOf[TextNode]) {
          val paragraph = new Paragraph(0, "", "p")
          val text = element.asInstanceOf[TextNode].text().trim
          if(text.length > 0) {
            paragraph.addText(text)
            section.addParagraphs(List(paragraph))
          }
        }
        else {
          val extractor: LinkExtractor = new LinkExtractor(extractionContext)
          val traversor: NodeTraversor = new NodeTraversor(extractor)
          traversor.traverse(element)
          if (extractor.getParagraphs.size() > 0){
            section.addParagraphs(extractor.getParagraphs.asScala.toList)
            section.addErrors(extractor.getErrors.asScala.toList)
          }
          else if(extractor.getTables.size() > 0){
            section.addParagraphs(extractor.getParagraphs.asScala.toList)
            section.addErrors(extractor.getErrors.asScala.toList)
          }
        }
      section
    }
  }

  @deprecated
  private def extractTextParagraph(text: String): (String, Int) = {
    var tempText: String = StringEscapeUtils.unescapeHtml4(text)
    tempText = WikiUtil.cleanSpace(tempText).trim
    if (tempText.contains("\\")) {
      tempText = tempText.replace("\\", "")
    }
    var escapeCount: Int = 0
    if (tempText.contains("\"") && !(tempText.trim == "\"")) {
      tempText = tempText.replace("\"", "\\\"")
      escapeCount = org.apache.commons.lang3.StringUtils.countMatches(tempText, "\\")
    }
    else if (tempText.trim == "\"") {
      tempText = ""
    }
    (WikiUtil.cleanSpace(tempText).trim, tempText.length - escapeCount)
  }

  private def cleanHtml(str: String): String = {
    val text = StringEscapeUtils.unescapeHtml4(str)
    StringEscapeUtils.unescapeJava(text.replaceAll("\\n", ""))
  }

  protected def getJsoupDoc(html: String): Document = {
    val doc = Jsoup.parse(cleanHtml(html))

    //delete queries
    for(query <- cssSelectorConfigMap.removeElements)
      for(item <- doc.select(query).asScala)
        item.remove()

    // get all tables and save them as is (after delete, since we want the same number of tables before and after)
    val tables = doc.select("table").clone().asScala

    //hack to number ol items (cant see a way to do it with css selectors in a sufficient way)
    for(ol <- doc.select("ol").asScala){
      val li = ol.children().select("li").asScala
      for(i <- li.indices)
        li(i).before("<span> " + (i+1) + ". </span>")
    }

    //replace queries
    for(css <- cssSelectorConfigMap.replaceElements) {
      val query = css.split("->")
      for (item <- doc.select(query(0)).asScala) {
        val before = query(1).substring(0, query(1).indexOf("$c"))
        val after = query(1).substring(query(1).indexOf("$c") + 2)
        item.before("<span>" + before  + "</span>")
        item.after("<span>" + after  + "</span>")
      }
    }

    //encircle notes
    for(css <- cssSelectorConfigMap.noteElements) {
      val query = css.split("\\s*->\\s*")
      for (item <- doc.select(query(0)).asScala) {
        val before = query(1).substring(0, query(1).indexOf("$c"))
        val after = query(1).substring(query(1).indexOf("$c") + 2)
        item.before("<span class='notebegin'>" + before + "</span>")
        item.after("<span class='noteend'>" + after  + "</span>")
      }
    }

    //revert to original tables, which might be corrupted by alterations above -> tables shall be untouched by alterations!
    val zw = doc.select("table").asScala
    if(zw.size != tables.size)
      throw new Exception("An error occurred due to differing table counts")
    for(i <- zw.indices)
      zw(i).replaceWith(tables(i))

    doc
  }

  @deprecated
  private def cleanUpWhiteSpaces(input : String): String =
  {
    //replaces multiple replace functions: tempText.replace("( ", "(").replace("  ", " ").replace(" ,", ",").replace(" .", ".");
    val sb = new StringBuilder()
    val chars = input.toCharArray

    var pos = 0
    var l = ' '

    while (pos < chars.length)
    {
      val c = chars(pos)
      if(c == ' ' || c == ',' || c == '.' || c == ')' || c == ']')        //
      {
        if(l != ' ')                //
          sb.append(l)
      }
      else
        sb.append(l)

      if(l == '(' || l == '[')        //
      {
        if(c != ' ')                //
          l = c
      }
      else
        l = c
      pos += 1
    }
    sb.append(l)

    sb.toString.substring(1)   //delete first space (see init of l)
  }

  protected def getNifIri(nifClass: String, beginIndex: Int, endIndex: Int): String ={
    val uri = URI.create(nifContextIri)
    var iri = uri.getScheme + "://" + uri.getHost + (if(uri.getPort > 0) ":" + uri.getPort else "") + uri.getPath + "?"
    val m = uri.getQuery.split("&").map(_.trim).collect{ case x if !x.startsWith("nif=") => x}
    iri += m.foldRight("")(_+"&"+_) + "nif=" + nifClass + "&char=" + beginIndex + "," + endIndex
    iri.replace("?&", "?")
  }

  protected def calculateText(paragraphs: Seq[Paragraph]): (String, Int) = {
    var length = 0
    var text = ""
    for (paragraph <- paragraphs)
      if (paragraph.getLength > 0) {
        if (text.length != 0) {
          if (paragraph.getTagName == "note") {
            text += paragraph.getText + "\n"
            length += paragraph.getLength + 1
          }
          else if (paragraph.getTagName.matches("h\\d")) {
            text += paragraph.getText + "\n"
            length += paragraph.getLength + 1
          }
          else if (Paragraph.FollowedByWhiteSpace(text)) {
            text += " " + paragraph.getText
            length += paragraph.getLength + 1
          }
          else {
            text += paragraph.getText
            length += paragraph.getLength
          }
        }
        else {
          if (paragraph.getTagName.matches("h\\d")) {
            text += paragraph.getText + "\n"
            length += paragraph.getLength + 1
          }
          else {
            text += paragraph.getText
            length += paragraph.getLength
          }
        }
      }
    assert(length == Paragraph.GetEscapedStringLength(text))
    (text, length)
  }

  protected class PageSection(
   var prev: Option[PageSection],
   var top: Option[PageSection],
   var next: Option[PageSection],
   var sub: Option[PageSection],
   val id: String,
   val title: String,
   val ref: String,
   var tableCount: Integer,
   val content: Seq[org.jsoup.nodes.Node]
 ) {

    private def getExtractedVersion(section: PageSection): Option[ExtractedSection] = sectionMap.get(section)

    def getSub: Option[ExtractedSection] = sub match{
      case Some(s) => getExtractedVersion(s)
      case None => None
    }
    def getTop: Option[ExtractedSection] = top match{
      case Some(s) => getExtractedVersion(s)
      case None => None
    }
    def getNext: Option[ExtractedSection] = next match{
      case Some(s) => getExtractedVersion(s)
      case None => None
    }
    def getPrev: Option[ExtractedSection] = prev match{
      case Some(s) => getExtractedVersion(s)
      case None => None
    }
  }

  protected class ExtractedSection(
    val section: PageSection,
    var paragraphs: List[Paragraph],
    var errors: List[String]
  ) extends PageSection(section.prev, section.top, section.next, section.sub, section.id, section.title, section.ref, section.tableCount, section.content)
  {
    private val nonSpaceChars = List('[', '(', '{')

    private var offset = 0
    def getSectionIri(offset: Int = offset): String = {
      this.offset = offset
      getNifIri("section", getBeginIndex(offset), getEndIndex(offset))
    }

/*    def getExtractedText: String ={
      calculateText(paragraphs)._1
    }

    def getExtractedLength: Int ={
      calculateText(paragraphs)._2 //to ensure the length calculation
    }*/

    def getBeginIndex(offset: Int = offset): Int = {
      this.offset = offset
      if(paragraphs.nonEmpty)
        paragraphs.head.getBegin(offset)
      else
        offset
    }

    def getEndIndex(offset: Int = offset): Int ={
      this.offset = offset
      if(paragraphs.nonEmpty)
        getBeginIndex(offset) + calculateText(paragraphs)._2
      else
        offset
    }

    def addParagraphs(p: List[Paragraph]) = paragraphs ++= p

    def addErrors(e: List[String]) = errors ++= e

/*    def whiteSpaceAfterSection = {
      val text = getExtractedText
      if(text.length > 0)
        !nonSpaceChars.contains(text.charAt(text.length - 1))
      else
        false
    }*/

    private var tablecounty = 0
    private def tablecount = {
      tablecounty= tablecounty+1
      tablecounty
    }

    def getTables = {
      val tables = for(paragraph <- paragraphs; tt <- paragraph.getTableHtml.entrySet().asScala)
        yield tablecount -> (tt.getKey, tt.getValue)
      tables.toMap
    }
  }
}