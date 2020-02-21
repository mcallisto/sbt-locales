package locales

import treehugger.forest._
import definitions._
import treehuggerDSL._
import locales.cldr._

object CodeGenerator {
  val autoGeneratedCommend  = "Auto-generated code from CLDR definitions, don't edit"
  val autoGeneratedISO639_2 = "Auto-generated code from ISO 639-2 data, don't edit"

  def buildClassTree(
    ldmls:         List[XMLLDML],
    only:          List[String],
    parentLocales: Map[String, List[String]],
    nsFilter:      String => Boolean
  ): Tree = {
    val langs = ldmls.map(_.scalaSafeName.split("_").toList.tail)
    // Root must always be available
    val root = ldmls.find(_.scalaSafeName == "_root").get

    val objectBlock = if (only.nonEmpty) {
      ldmls
        .filter(a => only.contains(a.scalaSafeName))
        .map(buildClassTree(root, langs, parentLocales, nsFilter))
    } else {
      ldmls.map(buildClassTree(root, langs, parentLocales, nsFilter))
    }
    val objectAll: Tree =
      OBJECTDEF("_all_") := BLOCK(
        LAZYVAL("all", "Array[LDML]") :=
          ARRAY(ldmls.map(x => REF(x.scalaSafeName))).withComment(autoGeneratedCommend)
      )

    BLOCK(
      List(
        IMPORT(REF("locales.cldr._")).withComment(autoGeneratedCommend),
        IMPORT(REF("locales.cldr.data.numericsystems._"))
      ) ++ (objectBlock :+ objectAll)
    ).inPackage("locales.cldr.data")
  }

  def findParent(
    root:          XMLLDML,
    langs:         List[List[String]],
    ldml:          XMLLDML,
    parentLocales: Map[String, List[String]]
  ): Option[String] =
    // http://www.unicode.org/reports/tr35/#Locale_Inheritance
    parentLocales
      .find(_._2.contains(ldml.fileName))
      .fold { // This searches based on the simple hierarchy resolution based on bundle_name
        // http://www.unicode.org/reports/tr35/#Bundle_vs_Item_Lookup
        ldml.scalaSafeName.split("_").toList.tail.reverse match {
          case x :: Nil if s"_$x" == root.scalaSafeName => None
          case _ :: Nil                                 => Some(root.scalaSafeName)
          case _ :: xs if langs.contains(xs.reverse) =>
            Some(xs.reverse.mkString("_", "_", ""))
          case _ =>
            sys.error("Shouldn't happen")
        }
      }(p => Some(p._1))

  def buildClassTree(
    root:          XMLLDML,
    langs:         List[List[String]],
    parentLocales: Map[String, List[String]],
    nsFilter:      String => Boolean
  )(ldml:          XMLLDML): Tree = {
    val ldmlSym                 = getModule("LDML")
    val ldmlNumericSym          = getModule("Symbols")
    val ldmlNumberCurrency      = getModule("NumberCurrency")
    val ldmlCurrencySym         = getModule("CurrencySymbol")
    val ldmlCurrencyDisplayName = getModule("CurrencyDisplayName")
    val ldmlCalendarSym         = getModule("CalendarSymbols")
    val ldmlCalendarPatternsSym = getModule("CalendarPatterns")
    val ldmlNumberPatternsSym   = getModule("NumberPatterns")
    val ldmlLocaleSym           = getModule("LDMLLocale")

    val parent = findParent(root, langs, ldml, parentLocales).fold(NONE)(v =>
      if (v.startsWith("_")) SOME(REF(v)) else SOME(REF(s"_$v"))
    )

    val ldmlLocaleTree = Apply(
      ldmlLocaleSym,
      LIT(ldml.locale.language),
      ldml.locale.territory.fold(NONE)(t => SOME(LIT(t))),
      ldml.locale.variant.fold(NONE)(v => SOME(LIT(v))),
      ldml.locale.script.fold(NONE)(s => SOME(LIT(s)))
    )

    val defaultNS = ldml.defaultNS.fold(NONE)(s => SOME(REF(s.id)))

    // Locales only use the default numeric system
    val numericSymbols = ldml.digitSymbols
      .filter {
        case (ns, _) => nsFilter.apply(ns.id)
      }
      .map {
        case (ns, symb) =>
          val decimal  = symb.decimal.fold(NONE)(s => SOME(LIT(s)))
          val group    = symb.group.fold(NONE)(s => SOME(LIT(s)))
          val list     = symb.list.fold(NONE)(s => SOME(LIT(s)))
          val percent  = symb.percent.fold(NONE)(s => SOME(LIT(s)))
          val minus    = symb.minus.fold(NONE)(s => SOME(LIT(s)))
          val perMille = symb.perMille.fold(NONE)(s => SOME(LIT(s)))
          val infinity = symb.infinity.fold(NONE)(s => SOME(LIT(s)))
          val nan      = symb.nan.fold(NONE)(s => SOME(LIT(s)))
          val exp      = symb.exp.fold(NONE)(s => SOME(LIT(s)))
          Apply(
            ldmlNumericSym,
            REF(ns.id),
            symb.aliasOf.fold(NONE)(n => SOME(REF(n.id))),
            decimal,
            group,
            list,
            percent,
            minus,
            perMille,
            infinity,
            nan,
            exp
          )
      }

    val currencies = ldml.currencies.map { c =>
      val symbols = LIST(c.symbols.map { s =>
        Apply(ldmlCurrencySym, LIT(s.symbol), LITOPTION(s.alt))
      })

      val displayNames = LIST(c.displayNames.map { n =>
        Apply(ldmlCurrencyDisplayName, LIT(n.name), LITOPTION(n.count))
      })

      Apply(ldmlNumberCurrency, LIT(c.currencyCode), symbols, displayNames)
    }

    val gc = ldml.calendar
      .map { cs =>
        Apply(
          ldmlCalendarSym,
          LIST(cs.months.map(LIT(_))),
          LIST(cs.shortMonths.map(LIT(_))),
          LIST(cs.weekdays.map(LIT(_))),
          LIST(cs.shortWeekdays.map(LIT(_))),
          LIST(cs.amPm.map(LIT(_))),
          LIST(cs.eras.map(LIT(_)))
        )
      }
      .fold(NONE)(s => SOME(s))

    val gcp = ldml.calendarPatterns
      .map { cs =>
        val dates = MAKE_MAP(
          cs.datePatterns.map(p => TUPLE(LIT(p._1), LIT(p._2)))
        )
        val times = MAKE_MAP(
          cs.timePatterns.map(p => TUPLE(LIT(p._1), LIT(p._2)))
        )
        Apply(ldmlCalendarPatternsSym, dates, times)
      }
      .fold(NONE)(s => SOME(s))

    val np = {
      val decimal  = ldml.numberPatterns.decimalFormat.fold(NONE)(s => SOME(LIT(s)))
      val percent  = ldml.numberPatterns.percentFormat.fold(NONE)(s => SOME(LIT(s)))
      val currency = ldml.numberPatterns.currencyFormat.fold(NONE)(s => SOME(LIT(s)))
      Apply(ldmlNumberPatternsSym, decimal, percent, currency)
    }

    OBJECTDEF(ldml.scalaSafeName).withParents(
      Apply(
        ldmlSym,
        parent,
        ldmlLocaleTree,
        defaultNS,
        LIST(numericSymbols),
        gc,
        gcp,
        LIST(currencies),
        np
      )
    )
  }

  def metadata(
    codes:          List[String],
    languages:      List[String],
    scripts:        List[String],
    territoryCodes: Map[String, String],
    iso3Languages:  Map[String, String],
    include:        Boolean
  ): Tree =
    BLOCK(
      OBJECTDEF("metadata") := BLOCK(
        LAZYVAL("isoCountries", "Array[String]") :=
          ARRAY(codes.filter(_ => include).map(LIT(_))).withComment(autoGeneratedCommend),
        LAZYVAL("isoLanguages", "Array[String]") :=
          ARRAY(languages.filter(_ => include).map(LIT(_))).withComment(autoGeneratedCommend),
        LAZYVAL("iso3Countries", "Map[String, String]") :=
          MAKE_MAP(territoryCodes.filter(_ => include).map { case (k, v) => LIT(k).ANY_->(LIT(v)) })
            .withComment(autoGeneratedCommend),
        LAZYVAL("iso3Languages", "Map[String, String]") :=
          MAKE_MAP(iso3Languages.filter(_ => include).map { case (k, v) => LIT(k).ANY_->(LIT(v)) })
            .withComment(autoGeneratedISO639_2),
        LAZYVAL("scripts", "Array[String]") :=
          ARRAY(scripts.map(LIT(_))).withComment(autoGeneratedCommend)
      )
    ).inPackage("locales.cldr.data")

  def localesProvider(): Tree =
    BLOCK(
      IMPORT(REF("locales.cldr.LocalesProvider")).withComment(autoGeneratedCommend),
      OBJECTDEF("provider") := BLOCK(
        )
    ).inPackage("locales.cldr.data")

  def numericSystems(ns: List[NumberingSystem], nsFilter: String => Boolean): Tree = {
    val ldmlNS = getModule("NumberingSystem")

    BLOCK(
      IMPORT(REF("locales.cldr.NumberingSystem")).withComment(autoGeneratedCommend),
      OBJECTDEF("numericsystems") := BLOCK(
        ns.filter(n => nsFilter(n.id))
          .map(s =>
            LAZYVAL(s.id, "NumberingSystem") :=
              Apply(ldmlNS, LIT(s.id), LIST(s.digits.toList.map(LIT(_))))
          )
      )
    ).inPackage("locales.cldr.data")
  }

  def calendars(c: List[Calendar], filter: String => Boolean): Tree = {
    val ldmlNS    = getModule("Calendar")
    val calendars = c.filter(c => filter(c.id))

    BLOCK(
      IMPORT(REF("locales.cldr.Calendar")).withComment(autoGeneratedCommend),
      OBJECTDEF("calendars") := BLOCK(
        (LAZYVAL("all", "List[Calendar]") := LIST(calendars.map(c => REF(c.safeName)))) +:
          calendars
            .map(c =>
              LAZYVAL(c.safeName, "Calendar") :=
                Apply(ldmlNS, LIT(c.id))
            )
      )
    ).inPackage("locales.cldr.data")
  }

  // Take an Option("foo") and generate the SOME(LIT("FOO"))
  private def LITOPTION(o: Option[_]): Tree = o.fold(NONE)(v => SOME(LIT(v)))

  def currencyData(c: CurrencyData, filters: Filters): Tree =
    BLOCK(
      IMPORT(REF("locales.cldr._")).withComment(autoGeneratedCommend),
      OBJECTDEF("currencydata") := BLOCK(
        VAL("currencyTypes", "List[CurrencyType]") := LIST(
          c.currencyTypes
            .filter(c => filters.currencyFilter.filter(c.currencyCode))
            .map { code: CurrencyType =>
              REF("CurrencyType").APPLY(LIT(code.currencyCode), LIT(code.currencyName))
            }
        ),
        VAL("fractions", "List[CurrencyDataFractionsInfo]") := LIST(
          c.fractions
            .filter(c => filters.currencyFilter.filter(c.currencyCode))
            .map { info: CurrencyDataFractionsInfo =>
              REF("CurrencyDataFractionsInfo").APPLY(
                LIT(info.currencyCode),
                LIT(info.digits),
                LIT(info.rounding),
                LITOPTION(info.cashDigits),
                LITOPTION(info.cashRounding)
              )
            }
        ),
        VAL("regions", "List[CurrencyDataRegion]") := LIST(
          c.regions
            .filter(c => filters.currencyRegionFilter.filter(c.countryCode))
            .map { region: CurrencyDataRegion =>
              REF("CurrencyDataRegion").APPLY(
                LIT(region.countryCode),
                LIST(
                  region.currencies
                    .filter(c => filters.currencyFilter.filter(c.currencyCode))
                    .map { currency: CurrencyDataRegionCurrency =>
                      REF("CurrencyDataRegionCurrency").APPLY(
                        LIT(currency.currencyCode),
                        LITOPTION(currency.from),
                        LITOPTION(currency.to),
                        LITOPTION(currency.tender)
                      )
                    }
                )
              )
            }
        ),
        VAL("numericCodes", "List[CurrencyNumericCode]") := LIST(
          c.numericCodes
            .filter(c => filters.currencyFilter.filter(c.currencyCode))
            .map { code: CurrencyNumericCode =>
              REF("CurrencyNumericCode").APPLY(LIT(code.currencyCode), LIT(code.numericCode))
            }
        )
      )
    ).inPackage("locales.cldr.data")
}
