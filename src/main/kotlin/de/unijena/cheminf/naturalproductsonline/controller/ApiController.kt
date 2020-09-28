package de.unijena.cheminf.naturalproductsonline.controller


import com.mongodb.MongoCommandException
import de.unijena.cheminf.naturalproductsonline.coconutmodel.mongocollections.PubFingerprintsCounts
import de.unijena.cheminf.naturalproductsonline.coconutmodel.mongocollections.PubFingerprintsCountsRepository
import de.unijena.cheminf.naturalproductsonline.coconutmodel.mongocollections.UniqueNaturalProduct
import de.unijena.cheminf.naturalproductsonline.coconutmodel.mongocollections.UniqueNaturalProductRepository
import de.unijena.cheminf.naturalproductsonline.model.AdvancedSearchModel
import de.unijena.cheminf.naturalproductsonline.utils.AtomContainerToUniqueNaturalProductService
import net.minidev.json.JSONObject
import net.sf.jniinchi.INCHI_OPTION
import org.openscience.cdk.exception.CDKException
import org.openscience.cdk.exception.InvalidSmilesException
import org.openscience.cdk.fingerprint.PubchemFingerprinter
import org.openscience.cdk.inchi.InChIGeneratorFactory
import org.openscience.cdk.interfaces.IAtomContainer
import org.openscience.cdk.isomorphism.*
import org.openscience.cdk.silent.SilentChemObjectBuilder
import org.openscience.cdk.smiles.SmiFlavor
import org.openscience.cdk.smiles.SmilesGenerator
import org.openscience.cdk.smiles.SmilesParser
import org.openscience.cdk.isomorphism.DfPattern
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.*
import java.lang.Math.ceil
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList


@RestController
@RequestMapping("/api")
class ApiController(val uniqueNaturalProductRepository: UniqueNaturalProductRepository) {
    /*
    * custom api code goes here
    *
    * basic REST-API generated by Spring
    * see @RepositoryRestResource annotation in repository class(es)
    *
    */

    val smilesParser: SmilesParser = SmilesParser(SilentChemObjectBuilder.getInstance())
    val smilesGenerator: SmilesGenerator = SmilesGenerator(SmiFlavor.Unique)
    internal var pubchemFingerprinter = PubchemFingerprinter(SilentChemObjectBuilder.getInstance())

    val options = mutableListOf(INCHI_OPTION.SNon, INCHI_OPTION.ChiralFlagOFF, INCHI_OPTION.AuxNone)

    val universalIsomorphismTester = UniversalIsomorphismTester()


    @Autowired
    lateinit var atomContainerToUniqueNaturalProductService: AtomContainerToUniqueNaturalProductService

    @Autowired
    lateinit var pubFingerprintsCountsRepository: PubFingerprintsCountsRepository


    /**
     * Advanced structure handling
     */

    @RequestMapping("/search/advanced" )
    @ResponseBody
    fun advancedSearch(@RequestParam("max-hits", required = false) maxHits:String, @RequestBody advancedSearchModel: AdvancedSearchModel): Map<String, Any>{

        println("catched advanced search")
        //println(advancedSearchModel.listOfSearchItems[1])
        try {
            return this.doAdvancedSearch(maxHits.toIntOrNull(), advancedSearchModel)
        } catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to "advanced",
                            "count" to 0,
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }
        }

    }

    /**
     *  Exact structure handling
     */
    @RequestMapping("/search/exact-structure")
    fun structureSearchBySmiles(@RequestParam("smiles") smiles: String, @RequestParam("type") type: String): Map<String, Any> {


        try {

            if (type == "smi") {
                return this.doExactStructureSearchBySmiles(URLDecoder.decode(smiles.trim(), "UTF-8"))
            } else {
                return this.doExactStructureSearchByInchi(URLDecoder.decode(smiles.trim(), "UTF-8"))
            }
        }catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to smiles,
                            "count" to 0,
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }
        }
    }


    /**
     *  Substructure handling
     */
    @RequestMapping("/search/substructure")
    fun substructureSearch(@RequestParam("smiles") smiles: String , @RequestParam("type") type: String , @RequestParam("max-hits") maxHits:String): Map<String, Any> {

        try {
            return this.doSubstructureSearch(URLDecoder.decode(smiles.trim(), "UTF-8"), type, maxHits.toIntOrNull())
        }catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to smiles,
                            "count" to 0,
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }
        }
    }


    /**
     * Simple (navigation bar) search handling
     */
    @RequestMapping("/search/simple")
    fun simpleSearch(@RequestParam("query") queryString: String): Map<String, Any> {
        /* switch between simple and simple heuristic search
        * the latter tries to guess the input type that could become harder with more search options
        */


        var decodedString = URLDecoder.decode(queryString.trim(), "UTF-8")
        decodedString = decodedString.replace("jjj", "%")

        try {
            return this.doSimpleSearchWithHeuristic(decodedString)
            // return this.doSimpleSearch(URLDecoder.decode(queryString.trim(), "UTF-8"))
        }catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to queryString,
                            "determinedInputType" to "none",
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }
        }
    }


    /**
     *  Similarity handling
     */
    @RequestMapping("/search/similarity")
    fun similaritySearch(@RequestParam("smiles") smiles: String , @RequestParam("max-hits") maxHits:String, @RequestParam("simThreshold") simThreshold:String): Map<String, Any> {

        var th: Int? = simThreshold.toIntOrNull()
        th = th

        try {
            return this.doSimilaritySearch(URLDecoder.decode(smiles.trim(), "UTF-8"), maxHits.toIntOrNull(), th)
        }catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to smiles,
                            "count" to 0,
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }

        }
    }


    /**
     *  Searches by chem class type
     */
    @RequestMapping("/search/chemclass")
    fun searchByChemicalClassification(@RequestParam("query") queryString: String) : Map<String, Any>{
        var decodedString = URLDecoder.decode(queryString.trim(), "UTF-8")

        try {
            return this.doChemclassSearch(decodedString)

        }catch (ex: Exception){

            when(ex) {
                is MongoCommandException, is OutOfMemoryError -> {
                    val other: List<UniqueNaturalProduct> = emptyList()
                    return mapOf(
                            "originalQuery" to queryString,
                            "count" to 0,
                            "naturalProducts" to  other
                    )
                }
                else -> throw ex
            }
        }
    }



    /**
    *  ************************************************************************************************
     *  Search functions
     */


    fun doChemclassSearch(query: String): Map<String, Any>{

        println("do chem class search")

        println(query)


        val results = this.uniqueNaturalProductRepository.findByChemclass(query)

        println(results.size)

        println("returning")

        return mapOf(
                "originalQuery" to query,
                "count" to results.size,
                "naturalProducts" to results
        )

    }


    fun doAdvancedSearch(maxHits:Int?, advancedSearchModel: AdvancedSearchModel) : Map<String, Any>{

        var maxResults = 100

        if(maxHits != null ){
            maxResults = maxHits
        }

        val results = this.uniqueNaturalProductRepository.advancedSearchWithCriteria(advancedSearchModel, maxResults)

        //results.shuffle()



        return mapOf(
                "originalQuery" to "advanced",
                "count" to results.size,
                "naturalProducts" to results
        )

    }






    fun doExactStructureSearchByInchi(smiles: String): Map<String, Any> {

        try {
            val queryAC: IAtomContainer = this.smilesParser.parseSmiles(smiles)
            val gen = InChIGeneratorFactory.getInstance().getInChIGenerator(queryAC, options)

            var queryInchi =  gen.getInchi()


            val results = this.uniqueNaturalProductRepository.findByInchi(queryInchi)

            return mapOf(
                    "originalQuery" to smiles,
                    "count" to results.size,
                    "naturalProducts" to results
            )
        } catch (e: InvalidSmilesException) {
            error("An InvalidSmilesException occured: ${e.message}")
        } catch (e: CDKException) {
            error("A CDKException occured: ${e.message}")
        }
    }

    fun doExactStructureSearchBySmiles(smiles: String) : Map<String, Any>{


        try {
            val queryAC: IAtomContainer = this.smilesParser.parseSmiles(smiles)
            val querySmiles = smilesGenerator.create(queryAC)


            val results = this.uniqueNaturalProductRepository.findByClean_smiles(querySmiles)

            return mapOf(
                    "originalQuery" to smiles,
                    "count" to results.size,
                    "naturalProducts" to results
            )
        } catch (e: InvalidSmilesException) {
            error("An InvalidSmilesException occured: ${e.message}")
        } catch (e: CDKException) {
            error("A CDKException occured: ${e.message}")
        }

    }






    fun doSimpleSearchWithHeuristic(query: String): Map<String, Any> {
        // determine type of input on very basic principles without validation



        var excludeWords = Regex("^(alpha-|beta-).*")

        println("do simple search with heuristic")


        var inchiPattern = Regex("^InChI=.*$")
        val inchikeyPattern = Regex("^[A-Z]{14}-[A-Z]{10}-[A-Z]$")
        val molecularFormulaPattern = Regex("C[0-9]+?H[0-9].+")
        var smilesPattern = Regex("^([^J][A-Za-z0-9@+\\-\\[\\]\\(\\)\\\\=#\$%]+)\$")
        val coconutPattern = Regex("^CNP[0-9]+?$")

        var naturalProducts : List<UniqueNaturalProduct>
        var determinedInputType : String


        try {
            this.smilesParser.parseSmiles(query)
            determinedInputType = "SMILES"
            println("detected SMILES")

        }catch (e: InvalidSmilesException){
            println(e)
            println("did not detected smiles")
            determinedInputType = "other"
        }

        if(determinedInputType=="SMILES"){

            try {
                val queryAC: IAtomContainer = this.smilesParser.parseSmiles(query)
                val querySmiles = this.smilesGenerator.create(queryAC)
                determinedInputType = "SMILES"
                println("detected SMILES")
                naturalProducts = this.uniqueNaturalProductRepository.findByUnique_smiles(querySmiles)
                if (naturalProducts.isEmpty()) {
                    println("second try SMILES")
                    naturalProducts = this.uniqueNaturalProductRepository.findByClean_smiles(querySmiles)

                }
            }catch (e: InvalidSmilesException){
                println("not a smiles")
                if(coconutPattern.containsMatchIn(query)) {
                    naturalProducts = this.uniqueNaturalProductRepository.findByCoconut_id(query)
                    determinedInputType = "COCONUT ID"
                }
                else if(inchiPattern.containsMatchIn(query)){
                    naturalProducts =  this.uniqueNaturalProductRepository.findByInchi(query)
                    determinedInputType = "InChi"
                }
                else if(inchikeyPattern.containsMatchIn(query)){
                    naturalProducts =  this.uniqueNaturalProductRepository.findByInchikey(query)
                    determinedInputType = "InChi Key"
                }
                else if(molecularFormulaPattern.containsMatchIn(query)){
                    naturalProducts = this.uniqueNaturalProductRepository.findByMolecular_formula(query)
                    determinedInputType = "molecular formula"
                }
                else {

                    //it was probably a name


                    naturalProducts = this.uniqueNaturalProductRepository.findByName(query)

                    if (naturalProducts == null || naturalProducts.isEmpty()) {
                        var altQuery = query
                        if(excludeWords.containsMatchIn(query)){
                            altQuery=altQuery.replace("alpha-", "")
                            altQuery=altQuery.replace("beta-", "")
                        }

                        naturalProducts = this.uniqueNaturalProductRepository.fuzzyNameSearch(altQuery)
                    }
                    determinedInputType = "name"
                }
            }

        }
        else if(coconutPattern.containsMatchIn(query)){
            naturalProducts =  this.uniqueNaturalProductRepository.findByCoconut_id(query)
            determinedInputType = "COCONUT ID"
        }
        else if(inchiPattern.containsMatchIn(query)){
            naturalProducts =  this.uniqueNaturalProductRepository.findByInchi(query)
            determinedInputType = "InChi"
        }
        else if(inchikeyPattern.containsMatchIn(query)){
            naturalProducts =  this.uniqueNaturalProductRepository.findByInchikey(query)
            determinedInputType = "InChi Key"
        }
        else if(molecularFormulaPattern.containsMatchIn(query)){
            naturalProducts = this.uniqueNaturalProductRepository.findByMolecular_formula(query)
            determinedInputType = "molecular formula"
        }
        else{
            //try to march by name
            println("apparently a name string")
            naturalProducts = this.uniqueNaturalProductRepository.findByName(query)

            if(naturalProducts == null || naturalProducts.isEmpty()){
                var altQuery = query
                if(excludeWords.containsMatchIn(query)){
                    altQuery=altQuery.replace("alpha-", "")
                    altQuery=altQuery.replace("beta-", "")
                }

                naturalProducts = this.uniqueNaturalProductRepository.fuzzyNameSearch(altQuery)
            }
            determinedInputType = "name"
        }
        println(determinedInputType)
        println("returning")




        return mapOf(
                "originalQuery" to query,
                "determinedInputType" to determinedInputType,
                "naturalProducts" to naturalProducts
        )
    }






    fun doSubstructureSearch(smiles: String, type: String, maxHitsSubmitted: Int?): Map<String, Any> {
        println("Entering substructure search")

        println(smiles)

        var maxResults = 100

        if(maxHitsSubmitted != null ){
            maxResults = maxHitsSubmitted
        }

        val hits = mutableListOf<UniqueNaturalProduct>()
        var counter: Int = 0

        try {
            val queryAC: IAtomContainer = this.smilesParser.parseSmiles(smiles)

            println(pubchemFingerprinter.getBitFingerprint(queryAC).asBitSet().toByteArray())

            // run $allBitsSet in mongo
            val barray = pubchemFingerprinter.getBitFingerprint(queryAC).asBitSet().toByteArray()
            val matchedList = this.uniqueNaturalProductRepository.findAllPubchemBitsSet(barray)

            //TODO get the exact bitset also? for faster substructure search of the first element

            println("found molecules with bits set")
            val pattern: Pattern
            // return a list of UNP:
            if(type=="default") {
                // for each UNP - convert to IAC and run the Ullmann
                pattern = Ullmann.findSubstructure(queryAC)
                for(unp in matchedList){//loop@

                    var targetAC : IAtomContainer = this.atomContainerToUniqueNaturalProductService.createAtomContainer(unp)

                    val match = pattern.match(targetAC)

                    if (match.isNotEmpty()) {
                        hits.add(unp)

                        //println(unp.coconut_id)

                        counter++

                        //if (counter==maxResults) break@loop

                    }
                }

            }else if(type=="df"){
                pattern = DfPattern.findSubstructure(queryAC)

                for(unp in matchedList){//loop@

                    var targetAC : IAtomContainer = this.atomContainerToUniqueNaturalProductService.createAtomContainer(unp)
                    if (pattern.matches(targetAC)) {
                        hits.add(unp)
                        //println(unp.coconut_id)
                        counter++
                        //if (counter==maxResults) break@loop

                    }
                }


            }else{
                //Vento-Foggia
                pattern = VentoFoggia.findSubstructure(queryAC)
                for(unp in matchedList){ //loop@

                    var targetAC : IAtomContainer = this.atomContainerToUniqueNaturalProductService.createAtomContainer(unp)

                    val match = pattern.match(targetAC)

                    if (match.isNotEmpty()) {
                        hits.add(unp)

                        println(unp.coconut_id)

                        //counter++

                        //if (counter==maxResults) break@loop

                    }
                }

            }


            hits.sortBy { it.heavy_atom_number }
            val hitsToReturn = hits.subList(0, minOf(hits.size , maxResults))

            println("ready to return results!")

            return mapOf(
                    "originalQuery" to smiles,
                    "count" to  hitsToReturn.size,
                    "naturalProducts" to hitsToReturn
            )

        } catch (e: InvalidSmilesException) {
            error("An InvalidSmilesException occured: ${e.message}")
        } catch (e: CDKException) {
            error("A CDKException occured: ${e.message}")
        }
    }




    fun doSimilaritySearch(smiles: String, maxHitsSubmitted: Int?, th: Int?): Map<String, Any> {


        var threshold: Double = 0.9

        if(th != null){
            threshold = th.toDouble()/100
        }

        println("entered similarity search")

        println(smiles)

        var maxResults = 100

        if(maxHitsSubmitted != null ){
            maxResults = maxHitsSubmitted
        }

        val hits = mutableListOf<UniqueNaturalProduct>()
        var counter: Int = 0

        try {
            val queryAC: IAtomContainer = this.smilesParser.parseSmiles(smiles)

            val s = pubchemFingerprinter.getBitFingerprint(queryAC).asBitSet()
            val queryPF = ArrayList<Int>()
            var i = s.nextSetBit(0)
            while (i != -1) {
                queryPF.add(i)
                i = s.nextSetBit(i + 1)
            }
            //queryPF is an array of indexes of ON bits for the query molecule

            var qLen : Int = queryPF.size

            var qmin = (ceil(qLen * threshold)).toInt()        // Minimum number of bits in results fingerprints
            var qmax = (qLen / threshold).toInt()              // Maximum number of bits in results fingerprints
            var ncommon = qLen - qmin + 1                      // Number of fingerprint bits in which at least 1 must be in common


            var allBits: MutableList<PubFingerprintsCounts> = pubFingerprintsCountsRepository.findAll()
            allBits.sortByDescending { it.count } //sorting to have the most frequent bits first

            var requestedBits = ArrayList<Int>()

            getbits@for (bit:PubFingerprintsCounts in allBits ){
                if(bit.id in queryPF){
                    requestedBits.add(bit.id)
                }
                if(requestedBits.size==ncommon)break@getbits

            }


            val matchedList = this.uniqueNaturalProductRepository.similaritySearch(requestedBits, queryPF, qmin, qmax, qLen, threshold, maxResults)

            //TODO redo a tanomoto here to be sure that the match is correct

            //hits.sortBy { it.heavy_atom_number }
            //val hitsToReturn = matchedList.subList(0, minOf(matchedList.size , maxResults))


            return mapOf(
                    "originalQuery" to smiles,
                    "count" to matchedList.size,
                    "naturalProducts" to matchedList
            )

        } catch (e: InvalidSmilesException) {
            error("An InvalidSmilesException occured: ${e.message}")
        } catch (e: CDKException) {
            error("A CDKException occured: ${e.message}")
        }



    }
}