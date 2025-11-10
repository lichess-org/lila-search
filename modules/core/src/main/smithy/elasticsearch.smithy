$version: "2"

namespace lila.search.es

use smithy.api#trait

/// Keyword subfield for text fields
structure KeywordSubfield {
    @required
    name: String
    normalizer: String
}

/// Marks a field as an Elasticsearch text field with full-text search capabilities
@trait(selector: "member")
structure textField {
    /// Search boost multiplier for this field
    boost: Integer
    /// Analyzer to use (e.g., "english", "standard")
    analyzer: String
    /// Optional keyword subfield for exact matching
    keywordSubfield: KeywordSubfield
}

/// Marks a field as an Elasticsearch keyword field for exact matching
@trait(selector: "member")
structure keywordField {
    /// Search boost multiplier for this field
    boost: Integer
    /// Whether to store doc values for sorting/aggregations
    docValues: Boolean = false
}

/// Marks a field as an Elasticsearch date field
@trait(selector: "member")
structure dateField {
    /// Date format (e.g., "epoch_millis")
    format: String
    /// Whether to store doc values for sorting/aggregations
    docValues: Boolean
}

/// Marks a field as an Elasticsearch short (16-bit integer) field
@trait(selector: "member")
structure shortField {
    /// Whether to store doc values for sorting/aggregations
    docValues: Boolean
}

/// Marks a field as an Elasticsearch integer (32-bit) field
@trait(selector: "member")
structure intField {
    /// Whether to store doc values for sorting/aggregations
    docValues: Boolean
}

/// Marks a field as an Elasticsearch boolean field
@trait(selector: "member")
structure booleanField {
    /// Whether to store doc values for sorting/aggregations
    docValues: Boolean
}
