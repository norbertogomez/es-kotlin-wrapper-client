package recipesearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.inbot.eskotlinwrapper.AsyncIndexRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest
import org.elasticsearch.client.configure
import org.elasticsearch.client.healthAsync
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder

class RecipeSearch(
    private val recipeRepository: AsyncIndexRepository<Recipe>,
    private val objectMapper: ObjectMapper
) {

    suspend fun healthStatus(): ClusterHealthStatus {
        return recipeRepository.client.cluster().healthAsync(ClusterHealthRequest()).status
    }

    suspend fun createNewIndex() {
        // BEGIN mapping_dsl
        recipeRepository.createIndex {
            configure {
                settings {
                    replicas = 0
                    shards = 1
                    // we have some syntactic sugar for adding custom analysis
                    // however we don't hava a complete DSL for this
                    // so we fall back to using put for things
                    // not in the DSL
                    addTokenizer("autocomplete") {
                        put("type", "edge_ngram")
                        put("min_gram", 2)
                        put("max_gram", 10)
                        put("token_chars", listOf("letter"))
                    }
                    addAnalyzer("autocomplete") {
                        put("tokenizer", "autocomplete")
                        put("filter", listOf("lowercase"))
                    }
                    addAnalyzer("autocomplete_search") {
                        put("tokenizer", "lowercase")
                    }
                }
                mappings {
                    text("allfields")
                    text("title") {
                        copyTo = listOf("allfields")
                        fields {
                            text("autocomplete") {
                                analyzer = "autocomplete"
                                searchAnalyzer = "autocomplete_search"
                            }
                        }
                    }
                    text("description") {
                        copyTo = listOf("allfields")
                    }
                    number<Int>("prep_time_min")
                    number<Int>("cook_time_min")
                    number<Int>("servings")
                    keyword("tags")
                    objField("author") {
                        text("name")
                        keyword("url")
                    }
                }
            }
        }
        // END mapping_dsl
    }

    suspend fun deleteIndex() {
        recipeRepository.deleteIndex()
    }

    @ExperimentalCoroutinesApi // will hopefully stabilize with next version
    // BEGIN index_recipes
    suspend fun indexExamples() {
        // use a small bulk size to illustrate how this can
        // work with potentially large amounts of files.
        recipeRepository.bulk(bulkSize = 3) {
            File("src/examples/resources/recipes")
                .listFiles { f -> f.extension == "json" }?.forEach {
                    val parsed = objectMapper.readValue<Recipe>(it.readText())
                    // lets use the sourceUrl as an id
                    // use create=false to allow updates
                    index(parsed.sourceUrl, parsed, create = false)
                }
        }
    }
    // END index_recipes

    // BEGIN search_recipes
    suspend fun search(query: String, from: Int, size: Int):
            SearchResponse<Recipe> {
        return recipeRepository.search {
            source(SearchSourceBuilder.searchSource().apply {
                from(from)
                size(size)
                query(
                    if (query.isBlank()) {
                        QueryBuilders.matchAllQuery()
                    } else {
                        QueryBuilders.boolQuery().apply {
                            should().apply {
                                add(QueryBuilders.matchPhraseQuery("title", query).boost(2.0f))
                                add(QueryBuilders.matchQuery("title", query).boost(2.0f))
                                add(QueryBuilders.matchQuery("description", query))
                            }
                        }
                    }
                )
            })
        }.toSearchResponse()
    }
    // END search_recipes

    // BEGIN autocomplete_recipes
    suspend fun autocomplete(query: String, from: Int, size: Int):
            SearchResponse<Recipe> {
        return recipeRepository.search {
            source(SearchSourceBuilder.searchSource().apply {
                from(from)
                size(size)
                query(
                    QueryBuilders.matchQuery("title.autocomplete", query)
                )
            })
        }.toSearchResponse()
    }
    // END autocomplete_recipes
}
