package qupath.ext.template
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileReader
import javafx.scene.paint.Stop
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.CycleMethod
import javafx.beans.value.ChangeListener
import javafx.concurrent.Task
import javafx.geometry.Pos
import javafx.scene.control.Alert.AlertType
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.geometry.HPos
import javafx.geometry.VPos
import javafx.scene.text.Font
// JavaFX & AWT/Swing imports for Channel Viewer
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.GridPane
import javafx.scene.text.Text
import javafx.scene.paint.Color
import javafx.stage.Stage

// QuPath imports
import qupath.lib.regions.RegionRequest
import java.awt.image.BufferedImage

import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import qupath.lib.common.Version
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.objects.PathObject
import org.apache.commons.math3.ml.distance.EuclideanDistance
import qupath.lib.gui.QuPathGUI
import javafx.stage.Modality
import javafx.application.Platform
import qupath.lib.objects.classes.PathClass
import java.awt.Color
import javafx.scene.control.ScrollPane
import javafx.stage.FileChooser
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.geometry.Insets
import javafx.scene.paint.Color
import javax.imageio.ImageIO
import javafx.scene.shape.Rectangle
import javafx.scene.paint.Color as FxColor


/**
 *
 * A QuPath extension that demonstrates a 'Cell Search Engine' with:
 *  - Quick Search (Morphology, Marker, Combined, Neighborhood)
 *  - Comprehensive Search (CSV-based clustering)
 *  phenotype finder
 *

 */
class DemoGroovyExtension implements QuPathExtension {
	private static List<Map<String, String>> cachedCSVRows = null
	private static String cachedCSVPath = null
	private static boolean hasNeuN = false

	String name = "Cell Search Engine"
	String description = "Offers quick and comprehensive cell similarity searches."
	Version QuPathVersion = Version.parse("v0.4.0")

	@Override
	void installExtension(QuPathGUI qupath) {
		def mainMenu = qupath.getMenu("Extensions>" + name, true)

		// --- QUICK CELL SEARCH ---
		def quickSearchMenu = new Menu("Quick Cell Search")



		def UnifiedSearchItem = new MenuItem("Unified Search")
		UnifiedSearchItem.setOnAction(e -> { runUnifiedSearch(qupath) })
		quickSearchMenu.getItems().addAll(UnifiedSearchItem)

		// --- COMPREHENSIVE SEARCH ---
		def comprehensiveMenu = new Menu("Comprehensive Search")


		def csvClusterItem = new MenuItem("Brain Hierarchy Level Analysis")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)

		def phenotypeFinderItem = new MenuItem("Phenotype Finder")
		phenotypeFinderItem.setOnAction(e -> runPhenotypeFinder(qupath))
		comprehensiveMenu.getItems().add(phenotypeFinderItem)

		def cosineSimilarityItem = new MenuItem("Cell Similarity Search")
		cosineSimilarityItem.setOnAction(e -> runCosineSimilaritySearch(qupath))
		comprehensiveMenu.getItems().add(cosineSimilarityItem)

		def resetRegionItem = new MenuItem("Reset Region Highlights")
		resetRegionItem.setOnAction(e -> resetRegionHighlights(qupath))

		// 5) Add Channel Viewer item
		def channelViewerItem = new MenuItem("Channel Viewer")
		channelViewerItem.setOnAction { e ->
			runChannelViewer(qupath)
		}
		def cellViewerItem = new MenuItem("Cell Viewer")
		cellViewerItem.setOnAction { e -> runCellViewer(qupath) }
		def exprMatrixItem = new MenuItem("Expression Matrix")
		exprMatrixItem.setOnAction { e ->
			runExpressionMatrix(qupath)
		}
		def queryReportItem = new MenuItem("Query Report")
		queryReportItem.setOnAction { e -> runQueryReport(qupath) }



		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu, resetRegionItem,channelViewerItem,cellViewerItem, exprMatrixItem,queryReportItem)
	}

// Unified Search that intelligently switches between Neighborhood and Multi-Query modes
// depending on the number of selected cells and chosen operation

	private static HBox partitionCheckboxes(List<CheckBox> checkboxes, int numCols) {
		int per = (int)Math.ceil(checkboxes.size()/numCols)
		def cols = []
		(0..<numCols).each { i ->
			int s = i*per, e = Math.min(s+per, checkboxes.size())
			def vb = new VBox(5)
			checkboxes.subList(s,e).each { vb.children.add(it) }
			cols << vb
		}
		def hb = new HBox(10)
		cols.each { hb.children.add(it) }
		return hb
	}
// Full logic for Neighborhood Search
	private static void runNeighborhoodSearch(QuPathGUI qupath, PathObject targetCell, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, TextField tfRadius, TextField tfTopN, CheckBox cbLocalDensity, ProgressBar progressBar, Label progressLabel) {
		def imageData = qupath.getImageData()
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		def pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
		double radiusMicrons = tfRadius.getText().toDouble()
		double radiusPixels = radiusMicrons / pixelSize

		if (!cbLocalDensity.isSelected()) {

			runNeighborhoodSearchAfterDensity(qupath, targetCell, markerCheckboxes, morphCbs, surroundCheckboxes, tfRadius, tfTopN)
			return
		}

		Task<Void> task = new Task<Void>() {
			@Override
			protected Void call() throws Exception {
				updateMessage("Computing local density...")
				int total = allCells.size()
				def spatialIndex = [:].withDefault { [] }
				allCells.each { cell ->
					def r = cell.getROI()
					def gx = (int)(r.getCentroidX() / radiusPixels)
					def gy = (int)(r.getCentroidY() / radiusPixels)
					spatialIndex["${gx}_${gy}"] << cell
				}
				allCells.eachWithIndex { cell, i ->
					def r = cell.getROI()
					def cx = r.getCentroidX()
					def cy = r.getCentroidY()
					def gx = (int)(cx / radiusPixels)
					def gy = (int)(cy / radiusPixels)
					def count = 0
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							spatialIndex[key].each { neighbor ->
								def r2 = neighbor.getROI()
								def dx2 = r2.getCentroidX() - cx
								def dy2 = r2.getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= radiusPixels * radiusPixels)
									count++
							}
						}
					}
					count -= 1 // Exclude self
					cell.getMeasurementList().putMeasurement("Local Density (r=${(int)radiusPixels})", count)
					updateProgress(i + 1, total)
					updateMessage("Processed ${i + 1} / ${total} cells")
				}
				Platform.runLater {
					runNeighborhoodSearchAfterDensity(qupath, targetCell, markerCheckboxes, morphCbs, surroundCheckboxes, tfRadius, tfTopN)
					progressLabel.textProperty().unbind()
					progressLabel.setText("✅ Done")
					// Hide progress bar after completion
					progressBar.setVisible(false)
					progressLabel.setVisible(false)
				}
				return null
			}
		}
		progressBar.progressProperty().bind(task.progressProperty())
		progressLabel.textProperty().bind(task.messageProperty())
		Thread t = new Thread(task)
		t.setDaemon(true)
		t.start()
	}

	private static void runNeighborhoodSearchAfterDensity(QuPathGUI qupath, PathObject targetCell, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, TextField tfRadius, TextField tfTopN) {

		def imageData = qupath.getImageData()
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		def pixelSize = imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
		double radiusMicrons = tfRadius.getText().toDouble()
		double radiusPixels = radiusMicrons / pixelSize


		int topN = tfTopN.getText().toInteger()

		def roi = targetCell.getROI()
		def centerX = roi.getCentroidX()
		def centerY = roi.getCentroidY()

		def features = []
		markerCheckboxes.findAll { it.isSelected() }.each { features << "Cell: ${it.getText()} mean" }
		morphCbs.findAll { it.isSelected() }.each { features << "Cell: ${it.getText()}" }

		boolean useSpatial = surroundCheckboxes.any { it.isSelected() }
		def distCalc = new EuclideanDistance()

		if (useSpatial) {
			def selectedMarkers = surroundCheckboxes.findAll { it.isSelected() }*.getText().collect { "Cell: ${it} mean" }
			def cellCoordinates = allCells.collectEntries { cell ->
				def r = cell.getROI()
				[(cell): [r.getCentroidX(), r.getCentroidY()]]
			}
			def spatialIndex = [:].withDefault { [] }
			cellCoordinates.each { cell, coord ->
				def gx = (int)(coord[0] / radiusPixels)
				def gy = (int)(coord[1] / radiusPixels)
				spatialIndex["${gx}_${gy}"] << cell
			}
			def getNeighbors = { coord ->
				def gx = (int)(coord[0] / radiusPixels)
				def gy = (int)(coord[1] / radiusPixels)
				def neighbors = [] as Set
				for (dx in -1..1) {
					for (dy in -1..1) {
						neighbors.addAll(spatialIndex["${gx+dx}_${gy+dy}"])
					}
				}
				neighbors.findAll {
					def xy = cellCoordinates[it]
					def dx = xy[0] - coord[0]
					def dy = xy[1] - coord[1]
					(dx*dx + dy*dy) <= radiusPixels*radiusPixels
				}
			}

			def targetNeighborhood = getNeighbors([centerX, centerY])
			def avgVec = selectedMarkers.collect { marker ->
				def vals = targetNeighborhood.collect { c -> c.getMeasurementList().getMeasurementValue(marker) ?: 0.0 }
				vals.sum() / vals.size()
			}

			def cellMarkerMap = allCells.collectEntries { cell ->
				[(cell): selectedMarkers.collect { m -> cell.getMeasurementList().getMeasurementValue(m) ?: 0.0 }]
			}

			def scored = allCells.findAll { it != targetCell }.collect { cell ->
				def coord = cellCoordinates[cell]
				def neighborhood = getNeighbors(coord)
				def avg = selectedMarkers.indices.collect { i ->
					def values = neighborhood.collect { n -> cellMarkerMap[n][i] }
					values.sum() / values.size()
				}
				[cell, distCalc.compute(avgVec as double[], avg as double[])]
			}

			scored.sort { it[1] }
			def topCells = scored.take(topN).collect { it[0] }
			def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
			topCells.each { it.setPathClass(className) }


			hierarchy.getSelectionModel().setSelectedObjects([targetCell] + topCells, targetCell)
		} else if (!features.isEmpty()) {
			def targetVec = features.collect { targetCell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
			def scored = allCells.findAll { it != targetCell }.collect { cell ->
				def vec = features.collect { cell.getMeasurementList().getMeasurementValue(it) ?: 0.0 }
				[cell, distCalc.compute(targetVec as double[], vec as double[])]
			}
			scored.sort { it[1] }
			def topCells = scored.take(topN).collect { it[0] }
			def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
			topCells.each { it.setPathClass(className) }


			hierarchy.getSelectionModel().setSelectedObjects([targetCell] + topCells, targetCell)
		} else {
			def nearby = allCells.findAll {
				def dx = it.getROI().getCentroidX() - centerX
				def dy = it.getROI().getCentroidY() - centerY
				(dx*dx + dy*dy) <= radiusPixels*radiusPixels
			}
			def className = PathClass.fromString("Neighborhood-Run-${System.currentTimeMillis() % 100000}")
			nearby.each { it.setPathClass(className) }


			hierarchy.getSelectionModel().setSelectedObjects([targetCell] + nearby, targetCell)
		}

		Platform.runLater {
			hierarchy.fireHierarchyChangedEvent(null)
			qupath.getViewer().repaint()
		}
	}

	private static void runMultiQuerySearch(QuPathGUI qupath, List<PathObject> selected, List<CheckBox> markerCheckboxes, List<CheckBox> morphCbs, List<CheckBox> surroundCheckboxes, List<CheckBox> allOps, TextField tfRadius, TextField tfTopN, TextField tfWeight, ProgressBar progressBar, Label progressLabel) {

		def imageData = qupath.getImageData()
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		double radiusPx = tfRadius.getText().toDouble() / imageData.getServer().getPixelCalibration().getPixelWidthMicrons()
		int limit = tfTopN.getText().toInteger()
		double k = tfWeight.getText().toDouble()
		def useSpatial = surroundCheckboxes.any { it.isSelected() }

		Task<Void> task = new Task<Void>() {
			List<PathObject> resultCells = []
			@Override protected Void call() {
				try {
					def allMap = [:].withDefault { [] }
					if (useSpatial) {
						allCells.each {
							def r = it.getROI()
							def key = "${(int)(r.getCentroidX()/radiusPx)}_${(int)(r.getCentroidY()/radiusPx)}"
							allMap[key] << it
						}
					}

					def extract = { cell ->
						def vec = []
						markerCheckboxes.findAll { it.isSelected() }.each {
							vec << (cell.getMeasurementList().getMeasurementValue("Cell: ${it.getText()} mean") ?: 0.0)
						}
						morphCbs.findAll { it.isSelected() }.each {
							vec << (cell.getMeasurementList().getMeasurementValue("Cell: ${it.getText()}") ?: 0.0)
						}
						if (useSpatial) {
							def cx = cell.getROI().getCentroidX()
							def cy = cell.getROI().getCentroidY()
							def gx = (int)(cx / radiusPx)
							def gy = (int)(cy / radiusPx)
							def neighbors = []
							for (dx in -1..1) {
								for (dy in -1..1) {
									def key = "${gx+dx}_${gy+dy}"
									allMap[key]?.each {
										def r = it.getROI()
										def dx2 = r.getCentroidX() - cx
										def dy2 = r.getCentroidY() - cy
										if (dx2*dx2 + dy2*dy2 <= radiusPx*radiusPx && it != cell)
											neighbors << it
									}
								}
							}
							surroundCheckboxes.findAll { it.isSelected() }.each {
								def values = neighbors.collect { n -> n.getMeasurementList().getMeasurementValue("Cell: ${it.getText()} mean") ?: 0.0 }
								vec << (values ? values.sum() / values.size() : 0.0)
							}
						}
						return vec as double[]
					}

					def distCalc = new EuclideanDistance()
					def vectors = allCells.collectEntries { [(it): extract(it)] }

					if (allOps.find { it.getText() == "Competitive Boost" && it.isSelected() }) {
						def vecT = vectors[selected[0]]
						def vecP = vectors[selected[1]]
						def vecN = vectors[selected[2]]
						def pq = new PriorityQueue<>(limit, { a, b -> b.value <=> a.value } as Comparator)
						allCells.each { c ->
							if (c == selected[0]) return
							double dT = distCalc.compute(vecT, vectors[c])
							double dP = distCalc.compute(vecP, vectors[c])
							double dN = distCalc.compute(vecN, vectors[c])
							double score = dT + k * dP - k * dN
							if (pq.size() < limit) pq.add(new AbstractMap.SimpleEntry<>(c, score))
							else if (score < pq.peek().value) { pq.poll(); pq.add(new AbstractMap.SimpleEntry<>(c, score)) }
						}
						resultCells = pq.toArray().toList().sort { a, b -> a.value <=> b.value }.collect { it.key }
					} else {
						def neighborSets = []
						selected.eachWithIndex { center, idx ->
							updateMessage("Processing ${idx+1}/${selected.size()}...")
							updateProgress(idx, selected.size())
							def pq = new PriorityQueue<>(limit, { a, b -> b.value <=> a.value } as Comparator)
							def vecC = vectors[center]
							allCells.each { c ->
								if (c == center) return
								def score = distCalc.compute(vecC, vectors[c])
								if (pq.size() < limit) pq.add(new AbstractMap.SimpleEntry<>(c, score))
								else if (score < pq.peek().value) { pq.poll(); pq.add(new AbstractMap.SimpleEntry<>(c, score)) }
							}
							neighborSets << pq.toArray().toList().sort { a, b -> a.value <=> b.value }.collect { it.key } as Set
						}
						if (allOps.find { it.getText() == "Union" && it.isSelected() }) {
							resultCells = neighborSets.flatten().collect { it as PathObject }
						} else if (allOps.find { it.getText() == "Intersection" && it.isSelected() }) {
							resultCells = neighborSets.inject(neighborSets[0]) { a, b -> a.intersect(b) }.toList()
						} else if (allOps.find { it.getText() == "Subtract" && it.isSelected() }) {
							def base = neighborSets[0]
							def subtract = neighborSets[1..-1].flatten() as Set
							resultCells = (base - subtract).toList()
						} else if (allOps.find { it.getText() == "Contrastive" && it.isSelected() }) {
							resultCells = (neighborSets[0] - neighborSets[1]).collect { it as PathObject }
						}
					}

					resultCells.each { it.setPathClass(PathClass.fromString("Multi-Query-Search")) }
					Platform.runLater {
						hierarchy.getSelectionModel().setSelectedObjects(resultCells, null)
						progressLabel.textProperty().unbind()
						progressLabel.setText("✅ Done: ${resultCells.size()} cells")
						// Hide progress bar after completion
						progressBar.setVisible(false)
						progressLabel.setVisible(false)
					}
				} catch (Exception ex) {
					ex.printStackTrace()
					Platform.runLater {
						progressLabel.textProperty().unbind()
						progressLabel.setText("❌ Failed: ${ex.message}")
						// Hide progress bar on failure
						progressBar.setVisible(false)
						progressLabel.setVisible(false)
					}
					throw ex
				}
				return null
			}
		}

		progressBar.progressProperty().bind(task.progressProperty())
		progressLabel.textProperty().bind(task.messageProperty())
		Thread thread = new Thread(task); thread.setDaemon(true); thread.start()
	}
// Unified Dispatcher UI
	private static void runUnifiedSearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			new Alert(Alert.AlertType.WARNING, "No image data available.").show()
			return
		}
		def hierarchy = imageData.getHierarchy()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
		if (allCells.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "No cell detections found.").show()
			return
		}

		def measurementNames = allCells[0].getMeasurementList().getMeasurementNames()

		def markerLabels = measurementNames.findAll { it.startsWith("Cell: ") && it.endsWith(" mean") }
				.collect { it.replace("Cell: ", "").replace(" mean", "") }

		def markerCheckboxes = markerLabels.collect { new CheckBox(it) }
		def cbMarkerSelectAll = new CheckBox("Select All Markers")
		cbMarkerSelectAll.setOnAction { markerCheckboxes.each { it.setSelected(cbMarkerSelectAll.isSelected()) } }

		def morphCbs = ["Area", "Perimeter", "Circularity", "Max caliper", "Min caliper", "Eccentricity"]
				.collect { new CheckBox(it) }
		def cbMorphSelectAll = new CheckBox("Select All Morphological")
		cbMorphSelectAll.setOnAction { morphCbs.each { it.setSelected(cbMorphSelectAll.isSelected()) } }

		def surroundCheckboxes = markerLabels.collect { new CheckBox(it) }
		def cbSurroundSelectAll = new CheckBox("Select All Neighborhood Markers")
		cbSurroundSelectAll.setOnAction { surroundCheckboxes.each { it.setSelected(cbSurroundSelectAll.isSelected()) } }

		def cbLocalDensity = new CheckBox("Use Local Density")
		cbLocalDensity.setSelected(false)

		def cbUnion = new CheckBox("Union")
		def cbIntersection = new CheckBox("Intersection")
		def cbSubtract = new CheckBox("Subtract")
		def cbContrastive = new CheckBox("Contrastive")
		def cbCompetitive = new CheckBox("Competitive Boost")
		def allOps = [cbUnion, cbIntersection, cbSubtract, cbContrastive, cbCompetitive]
		def enforceSingleOp = { changed -> allOps.each { if (it != changed) it.setSelected(false) } }
		allOps.each { cb -> cb.selectedProperty().addListener({ obs, oldV, newV -> if (newV) enforceSingleOp(cb) } as ChangeListener) }

		TextField tfTopN = new TextField("4000")
		TextField tfRadius = new TextField("50")
		TextField tfWeight = new TextField("1.0")
		ProgressBar progressBar = new ProgressBar(0.0)
		Label progressLabel = new Label()
		
		// Initially hide progress bar and label
		progressBar.setVisible(false)
		progressLabel.setVisible(false)

		Button btnRun = new Button("Run")
		Button btnExport = new Button("Export CSV")
		Button btnReset = new Button("Reset")
		Button btnClose = new Button("Close")
		Button btnSimMatrix = new Button("Similarity Matrix")
		btnSimMatrix.setOnAction {
			// 1 cell only
			def selected = qupath.getImageData()
					.getHierarchy()
					.getSelectionModel()
					.getSelectedObjects()
					.findAll { it.isCell() }
			if (selected.isEmpty()) {
				new Alert(AlertType.WARNING,
						"Please select at least one cell to view its similarity heatmap.").show()
				return
			}
			// Reconstruct your marker labels
			def featureLabels = markerLabels.collect { label -> "Cell: ${label} mean" }
			// Pass everything into the helper
			double radius = tfRadius.getText().toDouble()
			viewSelectionFeatureCorrelation(qupath)

		}

		Stage dialogStage = new Stage()

		btnRun.setOnAction {
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }

			if (selected.isEmpty()) {
				new Alert(AlertType.WARNING, "Please select at least one cell!").show()
				return
			}

			// Show progress bar and label when starting
			progressBar.setVisible(true)
			progressLabel.setVisible(true)
			progressLabel.setText("Starting...")

			if (selected.size() == 1 && !allOps.any { it.isSelected() }) {
				// Run standard neighborhood search with full parameter set
				runNeighborhoodSearch(
						qupath, selected[0],
						markerCheckboxes, morphCbs, surroundCheckboxes,
						tfRadius, tfTopN, cbLocalDensity,
						progressBar, progressLabel
				)
			} else if (selected.size() >= 2 && allOps.any { it.isSelected() }) {
				// Multi-query logic stays unchanged
				runMultiQuerySearch(
						qupath, selected as List,
						markerCheckboxes, morphCbs, surroundCheckboxes,
						allOps, tfRadius, tfTopN, tfWeight,
						progressBar, progressLabel
				)
			} else {
				new Alert(AlertType.WARNING, "Please check if your selection and operation match.").show()
				// Hide progress bar if operation fails
				progressBar.setVisible(false)
				progressLabel.setVisible(false)
			}
		}

		btnReset.setOnAction {

			allCells.each { it.setPathClass(null) }
			hierarchy.getSelectionModel().clearSelection()


			Platform.runLater {
				hierarchy.fireHierarchyChangedEvent(null)
				qupath.getViewer().repaint()
			}
		}
		btnClose.setOnAction { dialogStage.close() }

		btnExport.setOnAction {
			def sel = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (sel.isEmpty()) { new Alert(Alert.AlertType.WARNING, "No cells to export.").show(); return }
			def chooser = new FileChooser()
			chooser.setTitle("Export CSV")
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"))
			def file = chooser.showSaveDialog(dialogStage)
			if (file) {
				file.withPrintWriter { pw ->
					pw.println("CentroidX,CentroidY")
					sel.each {
						def r = it.getROI()
						pw.println("${r.getCentroidX()},${r.getCentroidY()}")
					}
				}
				new Alert(Alert.AlertType.INFORMATION, "Exported ${sel.size()} cells.").show()
			}
		}

		// Create scrollable sections for each checkbox group
		def createScrollableSection = { String title, CheckBox selectAll, List<CheckBox> checkboxes, int maxHeight ->
			def content = new VBox(5)
			content.getChildren().addAll(selectAll, partitionCheckboxes(checkboxes, 4))
			
			def scrollPane = new ScrollPane(content)
			scrollPane.setFitToWidth(true)
			scrollPane.setMaxHeight(maxHeight)
			scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
			scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
			
			// Add padding to prevent scroll bar from covering content
			scrollPane.setPadding(new Insets(5, 15, 5, 5)) // top, right, bottom, left - extra right padding for scroll bar
			
			def section = new VBox(5, new Label(title), scrollPane)
			return section
		}

		// Create sections with scroll bars
		def markerSection = createScrollableSection("Marker Selections:", cbMarkerSelectAll, markerCheckboxes, 150)
		def morphSection = createScrollableSection("Morphological Features:", cbMorphSelectAll, morphCbs, 120)
		def neighborhoodSection = createScrollableSection("Neighborhood Markers:", cbSurroundSelectAll, surroundCheckboxes, 150)

		VBox layout = new VBox(10,
				markerSection,
				morphSection,
				neighborhoodSection,
				new HBox(10, new Label("Top N:"), tfTopN, new Label("Radius (µm):"), tfRadius, new Label("Weight k:"), tfWeight),
				new VBox(5, new Label("Other Features:"), cbLocalDensity),
				new VBox(5, new Label("Operation:"), partitionCheckboxes(allOps, 3)),
				new HBox(10, btnRun, btnExport, btnReset, btnSimMatrix,btnClose),
				progressBar, progressLabel
		)
		layout.setPadding(new Insets(20))
		dialogStage.setTitle("Unified Cell Search")
		dialogStage.initOwner(qupath.getStage())
		dialogStage.setScene(new Scene(layout))
		dialogStage.show()
	}

/**
 * For the highlighted cell, find all neighbors within radius (µm),
 * then compute the Pearson correlation between each pair of features
 * across that neighborhood, and display an m×m heatmap labeled by marker.
 */
	private static void viewSelectionFeatureCorrelation(QuPathGUI qupath) {
		// 1) Collect cells
		def selected = qupath.getImageData().getHierarchy()
				.getSelectionModel()
				.getSelectedObjects()
				.findAll { it.isCell() }
		if (selected.size() < 2) {
			new Alert(AlertType.WARNING,
					"Please run Unified Search first and then click the heatmap\n" +
							"– you need at least 2 cells selected!"
			).show()
			return
		}

		// 2) Find “Cell: … mean” features
		def names        = selected[0].getMeasurementList().getMeasurementNames()
		def featureNames = names.findAll {
			it.startsWith("Cell: ") && it.endsWith(" mean")
		}
		int m = featureNames.size(), k = selected.size()

		// 3) Build data matrix
		double[][] data = new double[m][k]
		featureNames.eachWithIndex { fname, fi ->
			selected.eachWithIndex { cell, ci ->
				data[fi][ci] = cell.getMeasurementList()
						.getMeasurementValue(fname) ?: 0.0
			}
		}

		// 4) Compute pairwise Pearson corr
		double[][] corr = new double[m][m]
		for (int i = 0; i < m; i++) {
			double meanI = data[i].sum()/k
			double stdI  = Math.sqrt(data[i].collect{ (it-meanI)**2 }.sum()/(k-1))
			for (int j = 0; j < m; j++) {
				double meanJ = data[j].sum()/k
				double cov   = (0..<k).sum{ t ->
					(data[i][t]-meanI)*(data[j][t]-meanJ)
				}/(k-1)
				double stdJ  = Math.sqrt(data[j].collect{ (it-meanJ)**2 }.sum()/(k-1))
				corr[i][j]   = cov / (stdI*stdJ + 1e-12)
			}
		}

		// 5) Shorten labels (“Cell: NeuN mean” → “NeuN”)
		def shortLabels = featureNames.collect { fn ->
			fn.replaceFirst(/^Cell:\s*/, "").replaceFirst(/\s*mean$/, "")
		}

		// 6) Build the heatmap grid with larger cells & padding
		GridPane gp = new GridPane()
		gp.hgap    = 4
		gp.vgap    = 4
		gp.padding = new Insets(10)

		// 6a) Column headers: fully vertical, wider, bold, slightly bigger font
		shortLabels.eachWithIndex { lab, c ->
			Label lbl = new Label(lab)
			lbl.rotate     = -90
			lbl.wrapText   = true
			lbl.maxWidth   = 60
			lbl.style      = "-fx-font-weight: bold; -fx-font-size: 11;"
			GridPane.setHalignment(lbl, HPos.CENTER)
			GridPane.setValignment(lbl, VPos.CENTER)
			gp.add(lbl, c+1, 0)
		}

		// 6b) Row labels + larger 30×30 cells
		shortLabels.eachWithIndex { lab, r ->
			Label rowLbl = new Label(lab)
			rowLbl.minWidth  = 100
			rowLbl.style     = "-fx-font-weight: bold; -fx-font-size: 12;"
			gp.add(rowLbl, 0, r+1)

			(0..<m).each { c ->
				double v = corr[r][c]
				Rectangle rect = new Rectangle(30, 30)
				rect.fill = getColorForValue(v * 2)  // maps –1…+1 → –2…+2
				Tooltip.install(rect, new Tooltip(String.format("%.2f", v)))
				gp.add(rect, c+1, r+1)
			}
		}

		// 7) Wrap in a ScrollPane
		ScrollPane heatmapScroll = new ScrollPane(gp)
		heatmapScroll.fitToWidth  = true
		heatmapScroll.fitToHeight = true
		HBox.setHgrow(heatmapScroll, Priority.ALWAYS)

		// 8) Vertical legend: taller and narrower ticks
		Label legendTitle = new Label("Feature Correlation (r)")
		legendTitle.rotate = -90
		legendTitle.style  = "-fx-font-weight: bold; -fx-font-size: 12;"

		def stops = [
				new Stop(0.0, Color.web("#2166ac")),
				new Stop(0.5, Color.web("#f7f7f7")),
				new Stop(1.0, Color.web("#b2182b"))
		]
		LinearGradient lg = new LinearGradient(
				0, 1, 0, 0, true, CycleMethod.NO_CYCLE, stops
		)
		Rectangle gradientBar = new Rectangle(20, 180)  // increased height
		gradientBar.fill = lg

		GridPane tickGrid = new GridPane()
		tickGrid.prefWidth = 40
		tickGrid.maxHeight = gradientBar.height
		def topRow = new RowConstraints(); topRow.vgrow = Priority.NEVER
		def midRow = new RowConstraints(); midRow.vgrow = Priority.ALWAYS
		def botRow = new RowConstraints(); botRow.vgrow = Priority.NEVER
		tickGrid.rowConstraints.addAll(topRow, midRow, botRow)

		Label lblMax = new Label("+1"); GridPane.setHalignment(lblMax, HPos.CENTER); GridPane.setRowIndex(lblMax, 0)
		Label lblMid = new Label("0");  GridPane.setHalignment(lblMid, HPos.CENTER); GridPane.setRowIndex(lblMid, 1)
		Label lblMin = new Label("-1"); GridPane.setHalignment(lblMin, HPos.CENTER); GridPane.setRowIndex(lblMin, 2)
		tickGrid.children.addAll(lblMax, lblMid, lblMin)

		// collapse spacing entirely
		HBox legendBox = new HBox(0, legendTitle, gradientBar, tickGrid)
		legendBox.alignment = Pos.CENTER
		legendBox.padding   = new Insets(10)

// now shove the bar 6px to the left, overlapping into the label’s space
		HBox.setMargin(gradientBar, new Insets(0, 0, 0, -6))

// the tickGrid can stay at its normal spot
// HBox root as before
		HBox root = new HBox(8, heatmapScroll, legendBox)
		root.alignment = Pos.CENTER_LEFT
		root.padding   = Insets.EMPTY

		// 10) Show stage with a minimum size
		Stage stage = new Stage()
		stage.title     = "Feature Correlation (n=${k} cells, m=${m} features)"
		stage.scene     = new Scene(root)
		stage.minWidth  = 800
		stage.minHeight = 800
		stage.resizable = true
		stage.show()
	}
	// --- CSV-BASED CLUSTER SEARCH ---
	static void runCSVClusterSearch(QuPathGUI qupath) {
		// Check if we already have cached CSV data from Phenotype Finder
		if (cachedCSVRows != null && cachedCSVPath != null) {
			// Show info about using cached data
			def alert = new Alert(AlertType.INFORMATION, 
				"Using cached CSV data from: ${cachedCSVPath}\n\n" +
				"This data was loaded from Phenotype Finder.\n" +
				"You can continue with Brain Community Level Analysis or upload a different CSV file.")
			alert.setTitle("Cached CSV Data Available")
			alert.initOwner(qupath.getStage())
			
			ButtonType useCached = new ButtonType("Use Cached Data")
			ButtonType uploadNew = new ButtonType("Upload New CSV")
			ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE)
			
			alert.getButtonTypes().setAll(useCached, uploadNew, cancel)
			
			def result = alert.showAndWait()
			if (result.isPresent()) {
				if (result.get() == useCached) {
					// Use cached data directly - open the main dialog with cached data
					openBrainHierarchyDialog(qupath, cachedCSVRows, cachedCSVPath)
					return
				} else if (result.get() == uploadNew) {
					// Clear cache and continue to normal flow
					cachedCSVRows = null
					cachedCSVPath = null
					hasNeuN = false
				} else {
					// Cancel
					return
				}
			} else {
				return
			}
		}
		
		Stage stage = new Stage()
		stage.setTitle("Brain Community Level Analysis")
		stage.initOwner(qupath.getStage()) // No modality!

		// --- UI Elements ---
		TextField filePathField = new TextField()
		filePathField.setEditable(false)

		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll(
				"level_1", "level_2", "level_3", "level_4", "level_5", "level_6"
		)
		comboBox.setValue("level_1")

		// NEW: subfilter text field (comma-separated). Optional.
		TextField filterField = new TextField()
		filterField.setPromptText("e.g. 1,3,4  (leave blank to use cell selection)")
		filterField.setPrefColumnCount(8)

		Slider toleranceSlider = new Slider(1, 50, 20)
		toleranceSlider.setShowTickLabels(true)
		toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10)
		toleranceSlider.setMinorTickCount(4)

		Button browseButton = new Button("Browse CSV")
		Tooltip.install(browseButton, new Tooltip("Upload brain_analysis.csv"))
		Button runButton = new Button("Run")
		runButton.setDisable(true)
		Button viewCorticalButton = new Button("View Cortical Layer")
		viewCorticalButton.setDisable(true)
		Button resetButton = new Button("Reset Highlights")

		// --- Layout ---
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10)
		grid.setVgap(10)

		// Row 0: CSV file chooser
		grid.add(new Label("Brain Analysis CSV File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		// Row 1: Cluster Level dropdown + Filter text field
		grid.add(new Label("Cluster Level:"), 0, 1)
		grid.add(comboBox, 1, 1)
		grid.add(new Label("Filter Value(s):"), 2, 1)
		grid.add(filterField, 3, 1)

		// Row 2: Tolerance slider
		grid.add(new Label("Tolerance (px):"), 0, 2)
		grid.add(toleranceSlider, 1, 2, 3, 1)

		// Row 3: Run / Reset buttons
		grid.add(runButton, 1, 3)
		grid.add(viewCorticalButton, 2, 3)
		grid.add(resetButton, 3, 3)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// --- Internal data cache ---
		def rows = []
		def header = []
		File csvFile = null
		
		// If we have cached data, use it
		if (cachedCSVRows != null && cachedCSVPath != null) {
			rows = cachedCSVRows
			csvFile = new File(cachedCSVPath)
			filePathField.setText(cachedCSVPath)
			runButton.setDisable(false)
		}

		// --- Browse Button Action ---
		browseButton.setOnAction({
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Select CSV File")
			fileChooser.getExtensionFilters().add(
					new FileChooser.ExtensionFilter("CSV Files", "*.csv")
			)
			def selected = fileChooser.showOpenDialog(qupath.getStage())
			if (selected != null) {
				filePathField.setText(selected.getAbsolutePath())
				csvFile = selected
				rows.clear()
				header.clear()
				csvFile.withReader { reader ->
					def lines = reader.readLines()
					if (!lines) return
					header.addAll(lines[0].split(","))
					lines[1..-1].each { line ->
						def parts = line.split(",")
						def row = [:]
						for (int i = 0; i < header.size(); i++) {
							row[header[i]] = (i < parts.length ? parts[i] : "")
						}
						rows << row
					}
				}
				
				// Cache the CSV data for the entire session
				cachedCSVRows = rows
				cachedCSVPath = selected.getAbsolutePath()
				hasNeuN = header.any { it == "NeuN" }
				runButton.setDisable(false)
				viewCorticalButton.setDisable(false)
			}
		})

		// --- View Cortical Layer Button Action ---
		viewCorticalButton.setOnAction({
			// Check if CSV has cortical_layers column
			if (rows.isEmpty() || !rows[0].containsKey("cortical_layers")) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No cortical layer data found in CSV.\nPlease ensure the CSV contains a 'cortical_layers' column.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}
			
			// Open cortical layer dialog
			openCorticalLayerDialog(qupath, rows, csvFile)
		})

		// --- Reset Button Action ---
		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy()
						.getDetectionObjects()
						.findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					def viewer = qupath.getViewer()
					def hierarchy = qupath.getImageData().getHierarchy()
					hierarchy.fireHierarchyChangedEvent(null)
					viewer.repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Highlights reset.")
					alert.initOwner(qupath.getStage())
					alert.showAndWait()
				}
			}
		})

		// --- Run Button Action ---
		runButton.setOnAction({
			if (!csvFile || rows.isEmpty()) return

			def imageData = qupath.getImageData()
			if (imageData == null) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No image data found.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def chosenLevel = comboBox.getValue()
			def tolerance = toleranceSlider.getValue()

			// 1) Parse filterField (comma-separated). If non-empty, skip selection logic.
			String filterText = filterField.getText()?.trim()
			def selectedLabels = [] as Set

			if (filterText) {
				// Split on commas, trim tokens → a set of cluster IDs
				filterText.split(",").each { token ->
					def v = token.trim()
					if (v) {
						selectedLabels << v
					}
				}
				// Because filterText was provided, we do NOT require any cells to be selected.
			} else {
				// 2) If filterField is blank, require at least one cell selected
				def selectedCells = hierarchy.getSelectionModel()
						.getSelectedObjects()
						.findAll { it.isCell() }
				if (selectedCells.isEmpty()) {
					def alert = new Alert(
							AlertType.WARNING,
							"⚠️ Please select at least one cell (or enter filter IDs)."
					)
					alert.initOwner(qupath.getStage())
					alert.showAndWait()
					return
				}

				// 3) Original logic: for each selected cell, find nearest row→label
				selectedCells.each { cell ->
					def cx = cell.getROI().getCentroidX()
					def cy = cell.getROI().getCentroidY()
					def nearest = rows.min { row ->
						if (!row.x || !row.y) return Double.MAX_VALUE
						def dx = (row.x as double) - cx
						def dy = (row.y as double) - cy
						return dx * dx + dy * dy
					}
					if (nearest != null && nearest[chosenLevel] != null) {
						selectedLabels << nearest[chosenLevel]
					}
				}
			}

			// 4) If after either path, selectedLabels is empty → warn
			if (selectedLabels.isEmpty()) {
				def alert = new Alert(
						AlertType.WARNING,
						"No matching clusters found in CSV (check filter IDs or selected cells)."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			// 5) Collect all rows whose `row[chosenLevel]` is in selectedLabels
			def matchingRows = rows.findAll { row ->
				selectedLabels.contains(row[chosenLevel])
			}

			// 6) Build spatial‐bin map for fast neighbor lookup
			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x / binSize)}_${(int)(y / binSize)}"
				cellMap[key] << it
			}

			// 7) For every matching CSV row, find all nearby cells (within `tolerance`)
			def matchedCells = [] as Set
			matchingRows.each { row ->
				if (row.x && row.y) {
					def cx = row.x as double
					def cy = row.y as double
					def gx = (int)(cx / binSize)
					def gy = (int)(cy / binSize)
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							def group = cellMap[key]
							group.each {
								def dx2 = it.getROI().getCentroidX() - cx
								def dy2 = it.getROI().getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance)) {
									matchedCells << it
								}
							}
						}
					}
				}
			}

			// 8) Assign a PathClass based on chosenLevel + joined labels
			String labelStr = selectedLabels.join("_").replaceAll("[^a-zA-Z0-9_]", "_")
			def pathClass = PathClass.fromString("Cluster-${chosenLevel}-${labelStr}")

			matchedCells.each { it.setPathClass(pathClass) }
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				def viewer = qupath.getViewer()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()

				def labelSummary = selectedLabels.join(", ")
				def alert = new Alert(
						AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} = [${labelSummary}]\n"
								+ "Found ${matchedCells.size()} cells."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
			}

			// 9) Export matched cell centroids to a CSV file
			def exportFile = new File(
					csvFile.getParent(),
					"matched_cells_${chosenLevel}_${selectedLabels.join('_')}.csv"
			)
			exportFile.withWriter { w ->
				w.write("CellX,CellY\n")
				matchedCells.each {
					def roi = it.getROI()
					w.write("${roi.getCentroidX()},${roi.getCentroidY()}\n")
				}
			}
			println "Exported to: ${exportFile.absolutePath}"
		})
	}

	static void openBrainHierarchyDialog(QuPathGUI qupath, List<Map<String, String>> cachedRows, String cachedPath) {
		Stage stage = new Stage()
		stage.setTitle("Brain Community Level Analysis")
		stage.initOwner(qupath.getStage())

		// --- UI Elements ---
		TextField filePathField = new TextField()
		filePathField.setEditable(false)
		filePathField.setText(cachedPath)

		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll(
				"level_1", "level_2", "level_3", "level_4", "level_5", "level_6"
		)
		comboBox.setValue("level_1")

		TextField filterField = new TextField()
		filterField.setPromptText("e.g. 1,3,4  (leave blank to use cell selection)")
		filterField.setPrefColumnCount(8)

		Slider toleranceSlider = new Slider(1, 50, 20)
		toleranceSlider.setShowTickLabels(true)
		toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10)
		toleranceSlider.setMinorTickCount(4)

		Button browseButton = new Button("Browse CSV")
		Tooltip.install(browseButton, new Tooltip("Upload brain_analysis.csv"))
		Button runButton = new Button("Run")
		runButton.setDisable(false) // Enable since we have cached data
		Button viewCorticalButton = new Button("View Cortical Layer")
		viewCorticalButton.setDisable(false) // Enable since we have cached data
		Button resetButton = new Button("Reset Highlights")

		// --- Layout ---
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10)
		grid.setVgap(10)

		grid.add(new Label("Brain Analysis CSV File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		grid.add(new Label("Cluster Level:"), 0, 1)
		grid.add(comboBox, 1, 1)
		grid.add(new Label("Filter Value(s):"), 2, 1)
		grid.add(filterField, 3, 1)

		grid.add(new Label("Tolerance (px):"), 0, 2)
		grid.add(toleranceSlider, 1, 2, 3, 1)

		grid.add(runButton, 1, 3)
		grid.add(viewCorticalButton, 2, 3)
		grid.add(resetButton, 3, 3)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// --- Internal data cache ---
		def rows = cachedRows
		def header = []
		File csvFile = new File(cachedPath)

		// --- Browse Button Action ---
		browseButton.setOnAction({
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Select CSV File")
			fileChooser.getExtensionFilters().add(
					new FileChooser.ExtensionFilter("CSV Files", "*.csv")
			)
			def selected = fileChooser.showOpenDialog(qupath.getStage())
			if (selected != null) {
				filePathField.setText(selected.getAbsolutePath())
				csvFile = selected
				rows.clear()
				header.clear()
				csvFile.withReader { reader ->
					def lines = reader.readLines()
					if (!lines) return
					header.addAll(lines[0].split(","))
					lines[1..-1].each { line ->
						def parts = line.split(",")
						def row = [:]
						for (int i = 0; i < header.size(); i++) {
							row[header[i]] = (i < parts.length ? parts[i] : "")
						}
						rows << row
					}
				}
				
				// Update cache with new data
				cachedCSVRows = rows
				cachedCSVPath = selected.getAbsolutePath()
				hasNeuN = header.any { it == "NeuN" }
			}
		})

		// --- View Cortical Layer Button Action ---
		viewCorticalButton.setOnAction({
			// Check if CSV has cortical_layers column
			if (rows.isEmpty() || !rows[0].containsKey("cortical_layers")) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No cortical layer data found in CSV.\nPlease ensure the CSV contains a 'cortical_layers' column.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}
			
			// Open cortical layer dialog
			openCorticalLayerDialog(qupath, rows, csvFile)
		})

		// --- Reset Button Action ---
		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy()
						.getDetectionObjects()
						.findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					def viewer = qupath.getViewer()
					def hierarchy = qupath.getImageData().getHierarchy()
					hierarchy.fireHierarchyChangedEvent(null)
					viewer.repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Highlights reset.")
					alert.initOwner(qupath.getStage())
					alert.showAndWait()
				}
			}
		})

		// --- Run Button Action ---
		runButton.setOnAction({
			if (!csvFile || rows.isEmpty()) return

			def imageData = qupath.getImageData()
			if (imageData == null) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No image data found.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def chosenLevel = comboBox.getValue()
			def tolerance = toleranceSlider.getValue()

			String filterText = filterField.getText()?.trim()
			def selectedLabels = [] as Set

			if (filterText) {
				filterText.split(",").each { token ->
					def v = token.trim()
					if (v) {
						selectedLabels << v
					}
				}
			} else {
				def selectedCells = hierarchy.getSelectionModel()
						.getSelectedObjects()
						.findAll { it.isCell() }
				if (selectedCells.isEmpty()) {
					def alert = new Alert(
							AlertType.WARNING,
							"⚠️ Please select at least one cell (or enter filter IDs)."
					)
					alert.initOwner(qupath.getStage())
					alert.showAndWait()
					return
				}

				selectedCells.each { cell ->
					def cx = cell.getROI().getCentroidX()
					def cy = cell.getROI().getCentroidY()
					def nearest = rows.min { row ->
						if (!row.x || !row.y) return Double.MAX_VALUE
						def dx = (row.x as double) - cx
						def dy = (row.y as double) - cy
						return dx * dx + dy * dy
					}
					if (nearest != null && nearest[chosenLevel] != null) {
						selectedLabels << nearest[chosenLevel]
					}
				}
			}

			if (selectedLabels.isEmpty()) {
				def alert = new Alert(
						AlertType.WARNING,
						"No matching clusters found in CSV (check filter IDs or selected cells)."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def matchingRows = rows.findAll { row ->
				selectedLabels.contains(row[chosenLevel])
			}

			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x / binSize)}_${(int)(y / binSize)}"
				cellMap[key] << it
			}

			def matchedCells = [] as Set
			matchingRows.each { row ->
				if (row.x && row.y) {
					def cx = row.x as double
					def cy = row.y as double
					def gx = (int)(cx / binSize)
					def gy = (int)(cy / binSize)
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							def group = cellMap[key]
							group.each {
								def dx2 = it.getROI().getCentroidX() - cx
								def dy2 = it.getROI().getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance)) {
									matchedCells << it
								}
							}
						}
					}
				}
			}

			String labelStr = selectedLabels.join("_").replaceAll("[^a-zA-Z0-9_]", "_")
			def pathClass = PathClass.fromString("Cluster-${chosenLevel}-${labelStr}")

			matchedCells.each { it.setPathClass(pathClass) }
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				def viewer = qupath.getViewer()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()

				def labelSummary = selectedLabels.join(", ")
				def alert = new Alert(
						AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} = [${labelSummary}]\n"
								+ "Found ${matchedCells.size()} cells."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
			}

			def exportFile = new File(
					csvFile.getParent(),
					"matched_cells_${chosenLevel}_${selectedLabels.join('_')}.csv"
			)
			exportFile.withWriter { w ->
				w.write("CellX,CellY\n")
				matchedCells.each {
					def roi = it.getROI()
					w.write("${roi.getCentroidX()},${roi.getCentroidY()}\n")
				}
			}
			println "Exported to: ${exportFile.absolutePath}"
		})
	}

	static void runPhenotypeFinder(QuPathGUI qupath) {
		Closure makeCoordKey = { double x, double y -> "${Math.round(x)}_${Math.round(y)}" };
		def imageData = qupath.getImageData();
		if (imageData == null) {
			def alert = new Alert(Alert.AlertType.ERROR, "❌ No image open in QuPath.");
			alert.initOwner(qupath.getStage());
			alert.show();
			return;
		}
		def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
		if (allCells.isEmpty()) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No cell detections found.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}
		def measurementNames = allCells[0].getMeasurementList().getMeasurementNames()

		def markerLabels = measurementNames.findAll { name ->
			(name =~ /(?i).*NeuN.*/)
		}

		// Decide which phenotype palette to use
		Map<String, Color> phenotypeColors;
		if (markerLabels) {
			phenotypeColors = [
					"Glutamatergic"     : new Color(0, 206, 209),
					"GABAergic"         : new Color(0, 139, 139),
					"Cholinergic"       : new Color(255, 215, 0),
					"Catecholaminergic" : new Color(255, 140, 0),
					"Pericytes"         : new Color(139, 69, 19),
					"Endothelial cells" : new Color(0, 191, 255),
					"Oligodendrocytes"  : new Color(154, 205, 50),
					"Astrocytes"        : new Color(221, 160, 221),
					"Microglia"         : new Color(128, 0, 128),
					"Ependymal cells"   : new Color(173, 255, 47)
			];
		} else {
			phenotypeColors = [
					"Leukocytes"        : Color.RED,
					"B_cells"           : new Color(0, 128, 255),
					"Myeloid_cells"     : new Color(255, 165, 0),
					"Lymphocytes"       : Color.GREEN,
					"Helper_T_cells"    : new Color(255, 20, 147),
					"Helper_T_foxp3_cells"    : new Color(186, 85, 211),
					"Helper_T_GZMB_cells"     : new Color(0, 139, 139),
					"Cytotoxic_T_cells" : new Color(255, 69, 0),
					"Cytotoxic_T_Foxp3_cells" : new Color(199, 21, 133),
					"NK_cells"          : new Color(128, 0, 128),
					"Type1"       : new Color(0, 206, 209),
					"Dentric cells"   : new Color(70, 130, 180),
					"M1 macrophages"    : new Color(255, 140, 0),
					"M2 macrophages"    : new Color(139, 69, 19),
					"Regulatory T cells"      : new Color(127, 255, 212),
					"Memory T cells"          : new Color(173, 255, 47),
					"Stromal COLA1"      : new Color(154, 205, 50),
					"Stromal CD31"      : new Color(0, 191, 255),
					"Stromal aSMA"      : new Color(221, 160, 221),
					"Stromal FAP"       : new Color(147, 112, 219),
					"Epithelial"        : new Color(255, 215, 0),
					"Proliferation"     : new Color(0, 255, 127)
			];
		}
		
		// Check if we already have cached CSV data
		if (cachedCSVRows != null && cachedCSVPath != null) {
			// Show info about using cached data
			def alert = new Alert(AlertType.INFORMATION, 
				"Using cached CSV data from: ${cachedCSVPath}\n\n" +
				"This data was loaded from Brain Community Level Analysis.\n" +
				"You can continue with Phenotype Finder or upload a different CSV file.")
			alert.setTitle("Cached CSV Data Available")
			alert.initOwner(qupath.getStage())
			
			ButtonType useCached = new ButtonType("Use Cached Data")
			ButtonType uploadNew = new ButtonType("Upload New CSV")
			ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE)
			
			alert.getButtonTypes().setAll(useCached, uploadNew, cancel)
			
			def result = alert.showAndWait()
			if (result.isPresent()) {
				if (result.get() == useCached) {
					// Use cached data directly
					showPhenotypeDialog(qupath, imageData, cachedCSVRows, makeCoordKey, phenotypeColors, hasNeuN)
					return
				} else if (result.get() == uploadNew) {
					// Clear cache and continue to upload dialog
					cachedCSVRows = null
					cachedCSVPath = null
					hasNeuN = false
				} else {
					// Cancel
					return
				}
			} else {
				return
			}
		}

		// Upload CSV UI (only if no cached data)
		Stage uploadStage = new Stage();
		uploadStage.setTitle("Upload Brain Analysis CSV to view phenotypes");
		uploadStage.initModality(Modality.NONE);
		uploadStage.initOwner(qupath.getStage());

		TextField pathField = new TextField();
		pathField.setEditable(false);
		Button browseButton = new Button("Browse");
		Button runUploadButton = new Button("Run");
		Button cancelUploadButton = new Button("Cancel");

		HBox fileRow   = new HBox(10, pathField, browseButton);
		HBox buttonRow = new HBox(10, runUploadButton, cancelUploadButton);
		VBox layout    = new VBox(10, fileRow, buttonRow);
		layout.setPadding(new Insets(20));

		uploadStage.setScene(new Scene(layout));
		uploadStage.show();

		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select CSV File");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));

		browseButton.setOnAction {
			File file = chooser.showOpenDialog(qupath.getStage());
			if (file != null) {
				pathField.setText(file.getAbsolutePath());
			}
		}

		cancelUploadButton.setOnAction {
			uploadStage.close();
		}

		runUploadButton.setOnAction {
			File selectedCSV = new File(pathField.getText());
			if (selectedCSV == null || !selectedCSV.exists()) {
				def alert = new Alert(Alert.AlertType.WARNING, "Please select a valid CSV file.");
				alert.initOwner(qupath.getStage());
				alert.show();
				return;
			}

			// Read the CSV and cache it
			List<Map<String, String>> cachedRows = []
			selectedCSV.withReader { reader ->
				def lines = reader.readLines()
				def headers = lines[0].split(",").collect { it.trim() }
				hasNeuN = headers.any { it == "NeuN" }
				lines[1..-1].each { line ->
					def parts = line.split(",")
					def row = [:]
					headers.eachWithIndex { h, i ->
						row[h] = (i < parts.size()) ? parts[i].trim() : ""
					}
					cachedRows << row
				}
			}

			// Cache the data for the entire session
			cachedCSVRows = cachedRows
			cachedCSVPath = selectedCSV.getAbsolutePath()

			uploadStage.close()
			showPhenotypeDialog(qupath, imageData, cachedRows, makeCoordKey, phenotypeColors, hasNeuN)
		}
	}

	static void showPhenotypeDialog(QuPathGUI qupath, def imageData,
									List<Map<String, String>> cachedRows,
									Closure makeCoordKey,
									Map<String, Color> phenotypeColors,
									boolean hasNeuN) {
		// — Stage & basic controls —
		Stage stage = new Stage()
		stage.setTitle("Select Phenotype")
		stage.initModality(Modality.NONE)
		stage.initOwner(qupath.getStage())

		ComboBox<String> phenotypeCombo = new ComboBox<>()
		phenotypeCombo.getItems().addAll(phenotypeColors.keySet().sort())
		phenotypeCombo.setValue(phenotypeCombo.getItems().get(0))

		ComboBox<String> statusCombo = new ComboBox<>()
		statusCombo.setPromptText("Select status")

		Slider toleranceSlider = new Slider(1, 50, 10)
		toleranceSlider.setShowTickLabels(true)
		toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10)
		toleranceSlider.setMinorTickCount(4)
		toleranceSlider.setBlockIncrement(1)
		toleranceSlider.setSnapToTicks(true)

		Button runButton    = new Button("Run")
		Button cancelButton = new Button("Close")

		// — 1. Build per-phenotype status lists —
		def neuronStatuses = [
				"Mature_neuron", "Newly_born_neuron", "Differentiating_neuron",
				"Cholinergic_neuron", "Glutamatergic_neuron",
				"Catecholaminergic_neuron", "Apoptotic_neuron"
		]
		def statusOptionsMap = [
				"Glutamatergic"    : neuronStatuses,
				"GABAergic"        : neuronStatuses,
				"Cholinergic"      : neuronStatuses,
				"Catecholaminergic": neuronStatuses,
				"Astrocytes"       : [
						"Resting_astrocyte", "Reactive_astrocyte",
						"Mature_astrocyte", "Immature_astrocyte",
						"Newly_born_astrocyte", "Apoptotic_astrocyte"
				],
				"Microglia"        : ["Proliferating_microglia","Apoptotic_microglia"],
				"Oligodendrocytes" : [
						"Mature_oligodendrocyte","Myelinating_oligodendrocyte",
						"Non_myelinating_oligodendrocyte","Apoptotic_oligodendrocyte"
				],
				"Endothelial cells": ["Mature_endothelial","Reactive_endothelial","Proliferating_endothelial"]
		]

		// — repopulate statusCombo depending on hasNeuN —
		phenotypeCombo.setOnAction {
			def pheno = phenotypeCombo.getValue()
			statusCombo.getItems().clear()

			if (hasNeuN) {
				// Brain CSV: show detailed neuron/glia statuses
				def opts = statusOptionsMap.getOrDefault(pheno, [])
				statusCombo.getItems().add("Ignore")
				statusCombo.getItems().addAll(opts)
				statusCombo.setDisable(opts.isEmpty())
			} else {
				// Liver CSV: show tumor filter
				statusCombo.getItems().addAll("Ignore", "Yes", "No")
				statusCombo.setDisable(false)
			}

			statusCombo.setValue("Ignore")
		}
// Initialize on dialog open
		phenotypeCombo.getOnAction().handle(null)


		// — Layout —
		GridPane grid = new GridPane()
		// … after you declare runButton & cancelButton …
		Button viewHeatmapButton = new Button("View Heatmap")

		// ── in your GridPane layout block, add this row:
		grid.add(viewHeatmapButton, 0, 4, 2, 1)

		// ── then, *after* your runButton.setOnAction { … } but *before* closing the method:
		viewHeatmapButton.setOnAction {
			viewMarkerHeatmap(cachedRows,hasNeuN)
		}
		grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20))
		grid.add(new Label("Cell Type:"),       0, 0); grid.add(phenotypeCombo,   1, 0)
		Label statusLabel = new Label(hasNeuN ? "Cell Status:" : "Tumor Filter:")
		grid.add(statusLabel,  0, 1)
		grid.add(statusCombo,  1, 1)
		grid.add(new Label("Tolerance (µm):"),  0, 2); grid.add(toleranceSlider,  1, 2)
		grid.add(runButton,                     0, 3); grid.add(cancelButton,     1, 3)

		stage.setScene(new Scene(grid))
		stage.show()

		// — 3. Run/Close actions —
		cancelButton.setOnAction { stage.close() }

		runButton.setOnAction {
			String phenotype  = phenotypeCombo.getValue()
			String cellStatus = statusCombo.getValue()

			// 3a) Filter CSV rows by phenotype + status (Ignore = no additional filter)
			def filtered = cachedRows.findAll { row ->
				// must have the phenotype bit == 1
				if (!(row[phenotype] in ["1","1.0"])) return false

				if (hasNeuN) {
					// Brain CSV: detailed status columns
					if (cellStatus == "Ignore") return true
					return row[cellStatus] in ["1","1.0"]
				} else {
					// Liver CSV: tumor filter
					if (cellStatus == "Ignore") return true
					if (cellStatus == "Yes") return row["tumor"]?.toLowerCase() in ["true","1"]
					if (cellStatus == "No")  return row["tumor"]?.toLowerCase() in ["false","0"]
					return true
				}
			}

			// 3b) Existing matching logic unchanged:
			def hierarchy = imageData.getHierarchy()
			def allCells  = hierarchy.getDetectionObjects().findAll{ it.isCell() }
			def matched   = [] as Set

			if (hasNeuN) {
				double tol = toleranceSlider.getValue()
				def binSize = tol
				def cellMap = [:].withDefault{ [] }
				allCells.each {
					def x = it.getROI().getCentroidX()
					def y = it.getROI().getCentroidY()
					cellMap["${(int)(x/binSize)}_${(int)(y/binSize)}"] << it
				}
				filtered.each { row ->
					if (row["centroid_x"] && row["centroid_y"]) {
						double cx = row["centroid_x"] as double
						double cy = row["centroid_y"] as double
						int gx = (int)(cx/binSize), gy = (int)(cy/binSize)
						for (dx in -1..1) {
							for (dy in -1..1) {
								cellMap["${gx+dx}_${gy+dy}"].each {
									def dx2 = it.getROI().getCentroidX() - cx
									def dy2 = it.getROI().getCentroidY() - cy
									if ((dx2*dx2 + dy2*dy2) <= tol*tol)
										matched << it
								}
							}
						}
					}
				}
			} else {
				def csvKeys = filtered.collect{ makeCoordKey((it["Converted X µm"] as double),(it["Converted Y µm"] as double)) } as Set
				matched = allCells.findAll{
					csvKeys.contains(makeCoordKey(it.getROI().getCentroidX(), it.getROI().getCentroidY()))
				} as Set
			}

/** Build and show the heatmap window. */
			// 3c) Color & select
			def color = phenotypeColors.get(phenotype)
			def pathClass = PathClass.fromString("Pheno: "+phenotype)
			pathClass.setColor(
					(int)(color.red*255), (int)(color.green*255), (int)(color.blue*255)
			)
			matched.each{ it.setPathClass(pathClass) }
			Platform.runLater{
				hierarchy.fireHierarchyChangedEvent(null)
				qupath.getViewer().repaint()
			}
			hierarchy.getSelectionModel().setSelectedObjects(matched.toList(), null)

			def info =new Alert(AlertType.INFORMATION,
					"✅ Highlighted ${matched.size()} cells for '${phenotype}' (Status: ${cellStatus})"
			)
			info.initOwner(qupath.getStage())
			info.showAndWait()

		}

	}
	static Color getColorForValue(double v) {
		// 1) normalize into [0…1]
		double ratio = (v + 2.0) / 4.0
		ratio = Math.max(0.0, Math.min(1.0, ratio))

		// 2) define your endpoints
		Color deepBlue  = Color.web("#2166ac")
		Color neutral   = Color.web("#f7f7f7")
		Color deepRed   = Color.web("#b2182b")

		// 3) interpolate:
		if (ratio < 0.5) {
			// first half → deepBlue → neutral
			return deepBlue.interpolate(neutral, ratio * 2.0)
		} else {
			// second half → neutral → deepRed
			return neutral.interpolate(deepRed, (ratio - 0.5) * 2.0)
		}
	}

	static void viewMarkerHeatmap(List<Map<String,String>> cachedRows, boolean hasNeuN) {
		if (!cachedRows) {
			new Alert(AlertType.WARNING, "⚠️ No CSV data loaded!").show()
			return
		}

		// 1) Select markers & phenotypes
		List<String> markerCols, phenotypeCols
		if (hasNeuN) {
			markerCols = [ "NeuN","GFAP","IBA1","Olig2","MBP",
						   "PDGFRα","CD31","CollagenIV","Vimentin","CD68" ]
			phenotypeCols = [ "Glutamatergic","GABAergic","Cholinergic",
							  "Catecholaminergic","Astrocytes","Microglia",
							  "Oligodendrocytes","Endothelial cells",
							  "Pericytes","Ependymal cells" ]
		} else {
			markerCols = [ "DAPI","CD4","PD1","FOXP3","CD31","CD86","B220","CD8",
						   "aSMA","PDL1","Ki67","GZMB","FAP","CTLA4","CD11c","CD3e",
						   "CD206","F480","CD11b","CD45","Panck","Col1A1" ]
			phenotypeCols = [ "Leukocytes","B_cells","Myeloid_cells","Lymphocytes",
							  "Helper_T_cells","Helper_T_foxp3_cells","Helper_T_GZMB_cells",
							  "Cytotoxic_T_cells","Cytotoxic_T_Foxp3_cells","NK","Type1",
							  "Dentric cells","M1 macrophages","M2 macrophages",
							  "Regulatory T cells","Memory T cells","Stromal COLA1",
							  "Stromal CD31","Stromal aSMA","Stromal FAP",
							  "Epithelial","Proliferation" ]
		}

		// 2) Compute mean expression and counts
		def heatmapMatrix = [:].withDefault { [:] }
		def cellCounts    = [:]
		phenotypeCols.each { pheno ->
			def rows = cachedRows.findAll { it[pheno] in ["1","1.0"] }
			if (rows) {
				markerCols.each { m ->
					def vals = rows.collect { (it[m] ?: "0") as double }
					heatmapMatrix[pheno][m] = vals.sum() / vals.size()
				}
				cellCounts[pheno] = rows.size()
			}
		}

		// 3) Normalize to –2…+2
		def allVals = heatmapMatrix.values().collectMany { it.values() }
		double minV = allVals.min(), maxV = allVals.max()
		def normalize = { double v -> (v - minV)/(maxV - minV)*4 - 2 }

		// 4) Build the heatmap GridPane
		GridPane grid = new GridPane()
		grid.hgap = 2; grid.vgap = 2; grid.padding = new Insets(10)

		// 4a) Column headers
		markerCols.eachWithIndex { m, c ->
			Label lbl = new Label(m)
			lbl.rotate = -45
			grid.add(lbl, c + 1, 0)
		}

		// 4b) Rows of cells + counts
		phenotypeCols.eachWithIndex { pheno, r ->
			Label name = new Label(pheno)
			name.minWidth = 120
			grid.add(name, 0, r + 1)

			markerCols.eachWithIndex { m, c ->
				double raw  = heatmapMatrix[pheno][m] ?: 0.0
				Rectangle rect = new Rectangle(30, 20)
				rect.fill = getColorForValue(normalize(raw))
				Tooltip.install(rect, new Tooltip(
						"$pheno – $m: ${String.format('%.2f', raw)}"
				))
				grid.add(rect, c + 1, r + 1)
			}

			int cnt = cellCounts.getOrDefault(pheno, 0)
			double barW = Math.min(100.0, cnt / 200.0)
			Rectangle countBar = new Rectangle(barW, 20)
			countBar.fill = javafx.scene.paint.Color.GRAY
			Tooltip.install(countBar, new Tooltip("$pheno: $cnt cells"))
			grid.add(countBar, markerCols.size() + 1, r + 1)
			grid.add(new Label("$cnt"), markerCols.size() + 2, r + 1)
		}

		// 5) Wrap heatmap in a ScrollPane
		ScrollPane scroll = new ScrollPane(grid)
		scroll.fitToWidth  = true
		scroll.fitToHeight = true

		//
		// 6) Build **vertical** legend (smaller + anchored ticks)
		//

		// 6a) Title rotated vertically
		Label legendTitle = new Label("Mean expression")
		legendTitle.rotate = -90
		legendTitle.style  = "-fx-font-weight: bold;"

		// 6b) Smaller gradient bar (height: 100px)
		def stops = [
				   new Stop(0.0, Color.web("#3B4CC0")),  // cool (blue) end
				     new Stop(0.5, Color.web("#F5F5F5")),  // white midpoint
				     new Stop(1.0, Color.web("#D73027"))   // warm (red) end
				 ]
		LinearGradient lg = new LinearGradient(
				0, 1, 0, 0, true, CycleMethod.NO_CYCLE, stops
		)
		Rectangle gradientBar = new Rectangle(20, 150)
		gradientBar.fill = lg

		// 6c) Tick‐labels in GridPane for precise anchoring
		GridPane tickGrid = new GridPane()
		tickGrid.prefWidth = 30
		tickGrid.maxHeight = gradientBar.height

		def topRow = new RowConstraints(); topRow.vgrow = Priority.NEVER
		def midRow = new RowConstraints(); midRow.vgrow = Priority.ALWAYS
		def botRow = new RowConstraints(); botRow.vgrow = Priority.NEVER
		tickGrid.rowConstraints.addAll(topRow, midRow, botRow)

		Label lblMax = new Label("1")
		GridPane.setHalignment(lblMax, HPos.CENTER)
		GridPane.setRowIndex(lblMax, 0)

		Label lblMid = new Label("0.5")
		GridPane.setHalignment(lblMid, HPos.CENTER)
		GridPane.setRowIndex(lblMid, 1)

		Label lblMin = new Label("0")
		GridPane.setHalignment(lblMin, HPos.CENTER)
		GridPane.setRowIndex(lblMin, 2)

		tickGrid.children.addAll(lblMax, lblMid, lblMin)

		// 6d) Combine gradient + ticks
		HBox barWithTicks = new HBox(5, gradientBar, tickGrid)
		barWithTicks.alignment = Pos.CENTER_LEFT

		// 6e) Title + barWithTicks (tighter padding)
		HBox legend = new HBox(5, legendTitle, barWithTicks)
		legend.alignment = Pos.CENTER_LEFT
		legend.padding   = new Insets(5)

		// 7) Layout heatmap + legend
		HBox root = new HBox(5, scroll, legend)
		root.padding = new Insets(10)
		root.alignment = Pos.TOP_LEFT

		// 8) Show everything
		Stage heatmapStage = new Stage()
		heatmapStage.title = "Phenotype vs Marker Heatmap"
		heatmapStage.scene = new Scene(root)
		heatmapStage.show()
	}


	private static void runChannelViewer(QuPathGUI qupath) {
		Platform.runLater {
			// 1) Validate viewer & selection
			def viewer    = qupath.getViewer()
			def imageData = qupath.getImageData()
			if (viewer == null || imageData == null) {
				new Alert(AlertType.WARNING, "❌ No image open – please open an image first.")
						.showAndWait()
				return
			}
			def cells = imageData.getHierarchy()
					.getSelectionModel()
					.getSelectedObjects()
					.findAll { it.isCell() }
			if (cells.isEmpty()) {
				new Alert(AlertType.WARNING, "⚠️ Please select at least one cell before running Channel Viewer.")
						.showAndWait()
				return
			}
			
			// 2) Check if more than 6 cells selected and prompt user
			def cellsList = cells.toList()
			def cellsToShow = cellsList
			if (cells.size() > 6) {
				def dialog = new TextInputDialog("6")
				dialog.setTitle("Channel Viewer - Cell Selection")
				dialog.setHeaderText("You have ${cells.size()} cells selected")
				dialog.setContentText("How many cells would you like to view in the channel grid?")
				
				def result = dialog.showAndWait()
				if (!result.isPresent()) return
				
				try {
					int numCells = Integer.parseInt(result.get().trim())
					if (numCells < 1 || numCells > cells.size()) {
						new Alert(AlertType.ERROR, "Please enter a number between 1 and ${cells.size()}.").showAndWait()
						return
					}
					cellsToShow = cellsList.subList(0, numCells)
				} catch (NumberFormatException e) {
					new Alert(AlertType.ERROR, "Please enter a valid number.").showAndWait()
					return
				}
			}

			// 3) Build grid of 175×175 patches
			def server   = imageData.getServer()
			def channels = viewer.getImageDisplay().availableChannels()
			int nChan = channels.size(), nCell = cellsToShow.size()
			int w = 175, h = 175, halfW = w.intdiv(2), halfH = h.intdiv(2)

			def grid = new GridPane()
			grid.hgap = 5; grid.vgap = 5; grid.padding = new Insets(10)
			grid.add(new Text('Channel \\ Cell'), 0, 0)
			cellsToShow.eachWithIndex { cell, j -> grid.add(new Text("Cell ${j+1}"), j+1, 0) }
			channels.eachWithIndex { info, i -> grid.add(new Text(info.name), 0, i+1) }

			cellsToShow.eachWithIndex { cell, j ->
				def roi = cell.getROI()
				int cx = roi.getCentroidX() as int
				int cy = roi.getCentroidY() as int
				int x0 = Math.max(0, cx - halfW)
				int y0 = Math.max(0, cy - halfH)

				def req = RegionRequest.createInstance(server.getPath(), 1.0, x0, y0, w, h)
				BufferedImage multi = server.readBufferedImage(req)

				channels.eachWithIndex { info, i ->
					float[] vals = new float[w*h]
					info.getValues(multi, 0, 0, w, h, vals)
					float min = Float.MAX_VALUE, max = -Float.MAX_VALUE
					vals.each { v -> if (v<min) min=v; if (v>max) max=v }

					int argb = info.getColor()
					int r = (argb>>16)&0xFF, g = (argb>>8)&0xFF, b = argb&0xFF, a = (argb>>24)&0xFF
					Color fxColor = Color.rgb(r, g, b, a/255.0)

					BufferedImage patch = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
					for (int xi=0; xi<w; xi++) {
						for (int yi=0; yi<h; yi++) {
							int idx = yi*w + xi
							float norm = (max>min) ? (vals[idx]-min)/(max-min) : 0f
							norm = Math.max(0f, Math.min(1f, norm))
							int rr = ((int)(norm*fxColor.red*255)) & 0xFF
							int gg = ((int)(norm*fxColor.green*255)) & 0xFF
							int bb = ((int)(norm*fxColor.blue*255)) & 0xFF
							patch.setRGB(xi, yi, (0xFF<<24)|(rr<<16)|(gg<<8)|bb)
						}
					}

					def iv = new ImageView(SwingFXUtils.toFXImage(patch, null))
					iv.setFitWidth(w); iv.setFitHeight(h); iv.setPreserveRatio(false)
					grid.add(iv, j+1, i+1)
				}
			}

			// 3) Wrap grid in ScrollPane
			def scroll = new ScrollPane(grid)
			scroll.setFitToWidth(true); scroll.setFitToHeight(true)
			scroll.setPrefViewportWidth(Math.min((nCell+1)*w + 20, 1200))
			scroll.setPrefViewportHeight(Math.min((nChan+1)*h + 20, 800))

			// 4) Build stage and Save button
			Stage stage = new Stage()
			stage.initOwner(qupath.getStage())

			Button btnSave = new Button("Save Image…")
			btnSave.setOnAction {
				def chooser = new FileChooser()
				chooser.setTitle("Save Channel Grid")
				chooser.getExtensionFilters().addAll(
						new FileChooser.ExtensionFilter("PNG (*.png)", "*.png"),
						new FileChooser.ExtensionFilter("JPEG (*.jpg)", "*.jpg")
				)
				File file = chooser.showSaveDialog(stage)
				if (file != null) {
					WritableImage fxImage = grid.snapshot(new SnapshotParameters(), null)
					BufferedImage bimg     = SwingFXUtils.fromFXImage(fxImage, null)
					String fmt = file.name.toLowerCase().endsWith(".jpg") ? "jpg" : "png"
					ImageIO.write(bimg, fmt, file)
				}
			}

			// 5) Final layout
			def layout = new VBox(10, btnSave, scroll)
			layout.setPadding(new Insets(10))

			stage.setTitle("Channel×Cell Patches Grid")
			stage.setScene(new Scene(layout))
			stage.setWidth(scroll.getPrefViewportWidth() + 40)
			stage.setHeight(scroll.getPrefViewportHeight() + 90)
			stage.setResizable(true)
			stage.show()
		}
	}
	private static void runCellViewer(QuPathGUI qupath) {
		Platform.runLater {
			// 1) Validate viewer & selection
			def viewer    = qupath.getViewer()
			def imageData = qupath.getImageData()
			if (viewer == null || imageData == null) {
				new Alert(AlertType.WARNING, "❌ No image open – please open an image first.")
						.showAndWait()
				return
			}
			def cells = imageData.getHierarchy()
					.getSelectionModel()
					.getSelectedObjects()
					.findAll { it.isCell() }
			if (cells.isEmpty()) {
				new Alert(AlertType.WARNING, "⚠️ Please select at least one cell before running Cell Viewer.")
						.showAndWait()
				return
			}
			
			// 2) Check if more than 6 cells selected and prompt user
			def cellsList = cells.toList()
			def cellsToShow = cellsList
			if (cells.size() > 6) {
				def dialog = new TextInputDialog("6")
				dialog.setTitle("Cell Viewer - Cell Selection")
				dialog.setHeaderText("You have ${cells.size()} cells selected")
				dialog.setContentText("How many cells would you like to view in the cell grid?")
				
				def result = dialog.showAndWait()
				if (!result.isPresent()) return
				
				try {
					int numCells = Integer.parseInt(result.get().trim())
					if (numCells < 1 || numCells > cells.size()) {
						new Alert(AlertType.ERROR, "Please enter a number between 1 and ${cells.size()}.").showAndWait()
						return
					}
					cellsToShow = cellsList.subList(0, numCells)
				} catch (NumberFormatException e) {
					new Alert(AlertType.ERROR, "Please enter a valid number.").showAndWait()
					return
				}
			}

			// 3) Build grid of 75×75 patches around each selected cell
			def server   = imageData.getServer()
			def channels = viewer.getImageDisplay().availableChannels()
			int nChan = channels.size(), nCell = cellsToShow.size()
			int w = 75, h = 75, halfW = w.intdiv(2), halfH = h.intdiv(2)  // ◀◀◀ modified

			def grid = new GridPane()
			grid.hgap = 5; grid.vgap = 5; grid.padding = new Insets(10)
			grid.add(new Text('Channel \\ Cell'), 0, 0)
			cellsToShow.eachWithIndex { cell, j -> grid.add(new Text("Cell ${j+1}"), j+1, 0) }
			channels.eachWithIndex { info, i -> grid.add(new Text(info.name), 0, i+1) }

			cellsToShow.eachWithIndex { cell, j ->
				def roi = cell.getROI()
				int cx = roi.getCentroidX() as int
				int cy = roi.getCentroidY() as int
				int x0 = Math.max(0, cx - halfW)
				int y0 = Math.max(0, cy - halfH)

				def req = RegionRequest.createInstance(server.getPath(), 1.0, x0, y0, w, h)
				BufferedImage multi = server.readBufferedImage(req)

				channels.eachWithIndex { info, i ->
					float[] vals = new float[w*h]
					info.getValues(multi, 0, 0, w, h, vals)
					float min = Float.MAX_VALUE, max = -Float.MAX_VALUE
					vals.each { v -> if (v<min) min=v; if (v>max) max=v }

					int argb = info.getColor()
					int r = (argb>>16)&0xFF, g = (argb>>8)&0xFF, b = argb&0xFF, a = (argb>>24)&0xFF
					Color fxColor = Color.rgb(r, g, b, a/255.0)

					BufferedImage patch = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
					for (int xi=0; xi<w; xi++) {
						for (int yi=0; yi<h; yi++) {
							int idx = yi*w + xi
							float norm = (max>min) ? (vals[idx]-min)/(max-min) : 0f
							norm = Math.max(0f, Math.min(1f, norm))
							int rr = ((int)(norm*fxColor.red*255)) & 0xFF
							int gg = ((int)(norm*fxColor.green*255)) & 0xFF
							int bb = ((int)(norm*fxColor.blue*255)) & 0xFF
							patch.setRGB(xi, yi, (0xFF<<24)|(rr<<16)|(gg<<8)|bb)
						}
					}

					def iv = new ImageView(SwingFXUtils.toFXImage(patch, null))
					iv.setFitWidth(w); iv.setFitHeight(h); iv.setPreserveRatio(false)
					grid.add(iv, j+1, i+1)
				}
			}

			// 3) Wrap grid in ScrollPane
			def scroll = new ScrollPane(grid)
			scroll.setFitToWidth(true); scroll.setFitToHeight(true)
			scroll.setPrefViewportWidth(Math.min((nCell+1)*w + 20, 1200))
			scroll.setPrefViewportHeight(Math.min((nChan+1)*h + 20, 800))

			// 4) Build stage and Save button
			Stage stage = new Stage()
			stage.initOwner(qupath.getStage())

			Button btnSave = new Button("Save Image…")
			btnSave.setOnAction {
				def chooser = new FileChooser()
				chooser.setTitle("Save Cell Grid")
				chooser.getExtensionFilters().addAll(
						new FileChooser.ExtensionFilter("PNG (*.png)", "*.png"),
						new FileChooser.ExtensionFilter("JPEG (*.jpg)", "*.jpg")
				)
				File file = chooser.showSaveDialog(stage)
				if (file != null) {
					WritableImage fxImage = grid.snapshot(new SnapshotParameters(), null)
					BufferedImage bimg     = SwingFXUtils.fromFXImage(fxImage, null)
					String fmt = file.name.toLowerCase().endsWith(".jpg") ? "jpg" : "png"
					ImageIO.write(bimg, fmt, file)
				}
			}

			// 5) Final layout
			def layout = new VBox(10, btnSave, scroll)
			layout.setPadding(new Insets(10))

			stage.setTitle("Cell×Channel 75×75 Grid")  // ◀◀◀ modified
			stage.setScene(new Scene(layout))
			stage.setWidth(scroll.getPrefViewportWidth() + 40)
			stage.setHeight(scroll.getPrefViewportHeight() + 90)
			stage.setResizable(true)
			stage.show()
		}
	}

	private static void runExpressionMatrix(QuPathGUI qupath) {
		Platform.runLater {
			def imageData = qupath.getImageData()
			if (imageData == null) {
				new Alert(AlertType.WARNING, "❌ No image open – please open an image first.")
						.showAndWait()
				return
			}
			def allSelected = imageData.getHierarchy()
					.getSelectionModel()
					.getSelectedObjects()
					.findAll { it.isCell() }
			if (allSelected.isEmpty()) {
				new Alert(AlertType.WARNING, "⚠️ Please select at least one cell!")
						.showAndWait()
				return
			}

			// Prompt for N
			def dlg = new TextInputDialog("${allSelected.size()}")
			dlg.setTitle("Expression Matrix Size")
			dlg.setHeaderText("You have ${allSelected.size()} cells selected")
			dlg.setContentText("How many cells should the matrix show?")
			def result = dlg.showAndWait()
			if (!result.isPresent()) return

			int n
			try {
				n = Integer.parseInt(result.get().trim())
				if (n < 1 || n > allSelected.size()) throw new NumberFormatException()
			} catch (Exception e) {
				new Alert(AlertType.ERROR,
						"Enter a number between 1 and ${allSelected.size()}.")
						.showAndWait()
				return
			}
			def selected = allSelected.toList().subList(0, n)

			// Find marker‑mean features
			def names = selected[0].getMeasurementList().getMeasurementNames()
			def features = names.findAll { it.startsWith("Cell: ") && it.endsWith(" mean") }
			if (features.isEmpty()) {
				new Alert(AlertType.INFORMATION, "ℹ️ No marker‑mean measurements found.")
						.showAndWait()
				return
			}

			// Compute global min/max
			double globalMin = Double.POSITIVE_INFINITY
			double globalMax = Double.NEGATIVE_INFINITY
			selected.each { cell ->
				features.each { f ->
					double v = cell.getMeasurementList().getMeasurementValue(f) ?: 0.0
					globalMin = Math.min(globalMin, v)
					globalMax = Math.max(globalMax, v)
				}
			}

			// Build heatmap grid
			GridPane grid = new GridPane()
			grid.hgap = 2; grid.vgap = 2; grid.padding = new Insets(10)

			// Header row
			grid.add(new Label("Marker \\ Cell"), 0, 0)
			selected.eachWithIndex { cell, j ->
				grid.add(new Label("Cell ${j+1}"), j+1, 0)
			}

			// Fill cells with cool‑blue rectangles + tooltip
			features.eachWithIndex { fname, i ->
				def shortName = fname.replaceFirst(/^Cell:\s*/, "").replaceFirst(/\s*mean$/, "")
				grid.add(new Label(shortName), 0, i+1)
				selected.eachWithIndex { cell, j ->
					double v = cell.getMeasurementList().getMeasurementValue(fname) ?: 0.0
					// normalize
					double ratio = (v - globalMin)/(globalMax - globalMin)
					ratio = Math.max(0.0, Math.min(1.0, ratio))
					// white -> deep blue
					Color from = Color.web("#f7f7f7")
					Color to   = Color.web("#2166ac")
					Color fill = from.interpolate(to, ratio)

					Rectangle rect = new Rectangle(20, 20)
					rect.fill = fill
					Tooltip.install(rect, new Tooltip(String.format("%.2f", v)))
					grid.add(rect, j+1, i+1)
				}
			}

			// Wrap grid in scroll
			ScrollPane scroll = new ScrollPane(grid)
			scroll.fitToWidth  = true
			scroll.fitToHeight = true

			// Build vertical legend
			// height roughly = #features * (cellHeight + vgap)
			double legendHeight = features.size() * 22
			def stops = [
					new Stop(0.0, Color.web("#2166ac")), // top = deep blue (max)
					new Stop(1.0, Color.web("#f7f7f7"))  // bottom = white (min)
			]
			LinearGradient lg = new LinearGradient(
					0, 0, 0, 1, true, CycleMethod.NO_CYCLE, stops
			)
			Rectangle legendBar = new Rectangle(20, legendHeight)
			legendBar.fill = lg

			Label maxLabel = new Label(String.format("%.2f", globalMax))
			Label minLabel = new Label(String.format("%.2f", globalMin))
			VBox legendBox = new VBox(5,
					new Label("Mean Expression"),
					maxLabel,
					legendBar,
					minLabel
			)
			legendBox.alignment = Pos.CENTER
			legendBox.padding = new Insets(10)

			// Combine legend + heatmap
			HBox root = new HBox(10, legendBox, scroll)
			root.padding = new Insets(10)

			// Show stage
			Stage stage = new Stage()
			stage.setTitle("Expression Heatmap (${n}×${features.size()})")
			stage.initOwner(qupath.getStage())
			stage.setScene(new Scene(root))
			stage.show()
		}
	}
	/**
	 * Launches the Query Report workflow: choose between heatmap on highlighted cells or CSV file.
	 */

	private static void runQueryReport(QuPathGUI qupath) {
		Stage stage = new Stage()
		stage.setTitle("Generate Query Report")
		stage.initOwner(qupath.getStage())
		stage.initModality(Modality.NONE)

		ToggleGroup tg = new ToggleGroup()
		RadioButton rbExisting = new RadioButton("Existing highlighted cells").tap { it.setToggleGroup(tg); it.setSelected(true) }
		RadioButton rbCSV = new RadioButton("Upload CSV...").tap { it.setToggleGroup(tg) }

		// Inline file upload
		FileChooser chooser = new FileChooser()
		chooser.setTitle("Select CSV File")
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files","*.csv"))
		File selectedCSV = null
		TextField pathField = new TextField()
		pathField.setEditable(false)
		pathField.visibleProperty().bind(rbCSV.selectedProperty())
		Button browseButton = new Button("Browse...")
		browseButton.visibleProperty().bind(rbCSV.selectedProperty())
		browseButton.setOnAction {
			File file = chooser.showOpenDialog(qupath.getStage())
			if (file != null) {
				selectedCSV = file
				pathField.setText(file.getAbsolutePath())
			}
		}

		Button btnNext = new Button("Next")
		Button btnCancel = new Button("Cancel")
		btnNext.setOnAction {
			stage.close()
			if (rbExisting.isSelected()) {
				openQueryConfig(qupath, null)
			} else if (selectedCSV != null) {
				List<Map<String,String>> rows = []
				selectedCSV.withReader { reader ->
					def lines = reader.readLines()
					def headers = lines[0].split(',').collect{it.trim()}
					lines[1..-1].each { line ->
						def parts = line.split(',')
						def map = [:]
						headers.eachWithIndex{ h,i -> map[h]= (i<parts.size()?parts[i].trim():"") }
						rows << map
					}
				}
				openQueryConfig(qupath, rows)
			}
		}
		btnCancel.setOnAction { stage.close() }

		VBox layout = new VBox(10,
				rbExisting,
				rbCSV,
				new HBox(5, new Label("File:"), pathField, browseButton),
				new HBox(10, btnNext, btnCancel)
		)
		layout.setPadding(new Insets(20))
		stage.setScene(new Scene(layout))
		stage.show()
	}

	private static void openQueryConfig(QuPathGUI qupath, List<Map<String,String>> rows) {
		def imageData = qupath.getImageData()
		// Determine cells or CSV rows
		List<PathObject> cells = rows==null
				? new ArrayList<>(imageData.getHierarchy()
				.getSelectionModel()
				.getSelectedObjects()
				.findAll{ it.isCell() })
				: new ArrayList<>()

		if (rows==null && cells.isEmpty()) {
			new Alert(AlertType.WARNING, "⚠️ Please highlight at least one cell before running Query Report.").showAndWait()
			return
		}
		if (rows!=null && rows.isEmpty()) {
			new Alert(AlertType.WARNING, "⚠️ The selected CSV file contains no data.").showAndWait()
			return
		}

		Stage stage = new Stage()
		stage.setTitle("Configure Query Report")
		stage.initOwner(qupath.getStage())

		// --- Build X-axis selectors (markers & neighborhood) ---
		def allCells = imageData.getHierarchy().getDetectionObjects().findAll{ it.isCell() }
		def measurementNames = allCells[0].getMeasurementList().getMeasurementNames()
		def markerLabels = measurementNames.findAll{ it.startsWith("Cell: ") && it.endsWith(" mean") }
				.collect{ it.replace("Cell: ","").replace(" mean","") }
		def markerCbs = markerLabels.collect{ new CheckBox(it) }
		def cbMarkerAll = new CheckBox("Select All Markers")
		cbMarkerAll.setOnAction{ markerCbs.each{ it.setSelected(cbMarkerAll.isSelected()) } }
		def surroundCbs = markerLabels.collect{ new CheckBox(it) }
		def cbSurroundAll = new CheckBox("Select All Neighborhood Markers")
		cbSurroundAll.setOnAction{ surroundCbs.each{ it.setSelected(cbSurroundAll.isSelected()) } }

		// --- Build Y-axis selectors (cell types & subtypes) ---
		Map<String,List<String>> subtypeMap = [
				"Glutamatergic": ["Mature_neuron","Newly_born_neuron","Differentiating_neuron","Cholinergic_neuron","Glutamatergic_neuron","Catecholaminergic_neuron","Apoptotic_neuron"],
				"GABAergic":    ["Mature_neuron","Newly_born_neuron","Differentiating_neuron","Cholinergic_neuron","Glutamatergic_neuron","Catecholaminergic_neuron","Apoptotic_neuron"],
				"Cholinergic":  ["Mature_neuron","Newly_born_neuron","Differentiating_neuron","Cholinergic_neuron","Glutamatergic_neuron","Catecholaminergic_neuron","Apoptotic_neuron"],
				"Catecholaminergic": ["Mature_neuron","Newly_born_neuron","Differentiating_neuron","Cholinergic_neuron","Glutamatergic_neuron","Catecholaminergic_neuron","Apoptotic_neuron"],
				"Astrocytes":   ["Resting_astrocyte","Reactive_astrocyte","Mature_astrocyte","Immature_astrocyte","Newly_born_astrocyte","Apoptotic_astrocyte"],
				"Microglia":    ["Proliferating_microglia","Apoptotic_microglia"],
				"Oligodendrocytes": ["Mature_oligodendrocyte","Myelinating_oligodendrocyte","Non_myelinating_oligodendrocyte","Apoptotic_oligodendrocyte"],
				"Endothelial cells": ["Mature_endothelial","Reactive_endothelial","Proliferating_endothelial"],
				"Pericytes":    [],
				"Ependymal cells": []
		]
		Map<String,List<CheckBox>> subCbMap = [:]
		subtypeMap.each{ type, subs -> subCbMap[type] = [] }

		GridPane grid = new GridPane()
		grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20))

		// X-axis UI
		grid.add(new Label("Center Cell Markers:"), 0, 0)
		grid.add(new VBox(5, cbMarkerAll, partitionCheckboxes(markerCbs,4)), 1, 0)
		grid.add(new Label("Neighborhood Markers:"), 0, 1)
		grid.add(new VBox(5, cbSurroundAll, partitionCheckboxes(surroundCbs,4)), 1, 1)

		// Y-axis UI
		grid.add(new Label("Cell Types & Subtypes:"), 0, 2)
		VBox typeBox = new VBox(5)
		subtypeMap.each{ type, subs ->
			CheckBox cbType = new CheckBox(type)
			VBox subBox = new VBox(3)
			subs.each{ sub ->
				CheckBox cbSub = new CheckBox(sub)
				subBox.getChildren().add(cbSub)
				subCbMap[type].add(cbSub)
			}
			subBox.setPadding(new Insets(0,0,0,20))
			typeBox.getChildren().addAll(cbType, subBox)
		}
		ScrollPane yScroll = new ScrollPane(typeBox)
		yScroll.setFitToWidth(true); yScroll.setMaxHeight(200)
		grid.add(yScroll, 1, 2)

		// Buttons
		Button btnPlot = new Button("Plot"), btnCancel = new Button("Cancel")
		btnCancel.setOnAction{ stage.close() }
		btnPlot.setOnAction {
			// Collect selected X features
			List<String> selX = []
			markerCbs.findAll{ it.isSelected() }.each{ selX << it.getText() }
			surroundCbs.findAll{ it.isSelected() }.each{ selX << "Neighbor: ${it.getText()}" }
			// Collect selected Y groups
			List<String> selY = []
			subtypeMap.each{ type, subs ->
				if (typeBox.getChildren().find{ it instanceof CheckBox && it.getText()==type }.isSelected())
					selY << type
				subCbMap[type].findAll{ it.isSelected() }.each{ selY << it.getText() }
			}
			// Compute data matrix: mean measurement per group
			Map<String,Map<String,Double>> dataMatrix = [:]
			selY.each{ yGroup ->
				// filter cells by PathClass or skip grouping
				List<PathObject> groupCells = cells.findAll{
					it.getPathClass().getName() == yGroup
				}
				selX.each{ xFeat ->
					if (!dataMatrix.containsKey(xFeat)) dataMatrix[xFeat] = [:]
					double meanVal = 0.0
					if (!groupCells.isEmpty()) {
						meanVal = groupCells.collect{ cell ->
							cell.getMeasurementList().getMeasurementValue("Cell: ${xFeat.replace('Neighbor: ','')} mean") ?: 0.0
						}.sum() / groupCells.size()
					}
					dataMatrix[xFeat][yGroup] = meanVal
				}
			}
			showQueryHeatmap(stage, selX, selY, dataMatrix)
		}
		grid.add(new HBox(10, btnPlot, btnCancel), 0, 3, 2, 1)

		stage.setScene(new Scene(grid))
		stage.show()
	}

	private static void showQueryHeatmap(Stage owner, List<String> xLabels, List<String> yLabels,
										 Map<String,Map<String,Double>> dataMatrix) {
		Stage stage = new Stage()
		stage.setTitle("Query Heatmap")
		stage.initOwner(owner)

		GridPane gp = new GridPane()
		gp.setHgap(4); gp.setVgap(4); gp.setPadding(new Insets(10))

		for (int c = 0; c < xLabels.size(); c++) {
			Label lbl = new Label(xLabels[c])
			lbl.setRotate(-90); lbl.setWrapText(true); lbl.setMaxWidth(60)
			GridPane.setHalignment(lbl, HPos.CENTER); GridPane.setValignment(lbl, VPos.CENTER)
			gp.add(lbl, c+1, 0)
		}

		for (int r = 0; r < yLabels.size(); r++) {
			Label rowLbl = new Label(yLabels[r])
			rowLbl.setMinWidth(100); rowLbl.setStyle("-fx-font-weight:bold;")
			gp.add(rowLbl, 0, r+1)
			for (int c = 0; c < xLabels.size(); c++) {
				double v = dataMatrix.get(xLabels[c])?.get(yLabels[r]) ?: 0.0
				javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(30,30)
				rect.setFill(getFXColorForValue(v))
				Tooltip.install(rect, new Tooltip(String.format("%.2f", v)))
				gp.add(rect, c+1, r+1)
			}
		}

		ScrollPane scroll = new ScrollPane(gp)
		scroll.setFitToWidth(true); scroll.setFitToHeight(true)

		Label title = new Label("Value")
		title.setRotate(-90); title.setStyle("-fx-font-weight:bold;")
		def stops = [new Stop(0.0, javafx.scene.paint.Color.web("#2166ac")),
					 new Stop(0.5, javafx.scene.paint.Color.web("#f7f7f7")),
					 new Stop(1.0, javafx.scene.paint.Color.web("#b2182b"))]
		LinearGradient lg = new LinearGradient(0,1,0,0,true,CycleMethod.NO_CYCLE, stops)
		javafx.scene.shape.Rectangle gradBar = new javafx.scene.shape.Rectangle(20, yLabels.size()*30)
		gradBar.setFill(lg)

		Label lblMax = new Label("High")
		Label lblMid = new Label("Med")
		Label lblMin = new Label("Low")
		GridPane tick = new GridPane()
		tick.setPrefWidth(30)
		tick.add(lblMax,0,0); tick.add(lblMid,0,1); tick.add(lblMin,0,2)

		HBox legend = new HBox(4, title, gradBar, tick)
		legend.setAlignment(Pos.CENTER)
		legend.setPadding(new Insets(10))

		HBox root = new HBox(8, scroll, legend)
		root.setPadding(new Insets(10))

		stage.setScene(new Scene(root))
		stage.show()
	}

	// Utility color mapping for JavaFX heatmap
	private static javafx.scene.paint.Color getFXColorForValue(double v) {
		double ratio = Math.max(0.0, Math.min(1.0, v)) // adjust normalization as needed
		if (ratio < 0.5) return javafx.scene.paint.Color.web("#2166ac").interpolate(javafx.scene.paint.Color.web("#f7f7f7"), ratio*2)
		else return javafx.scene.paint.Color.web("#f7f7f7").interpolate(javafx.scene.paint.Color.web("#b2182b"), (ratio-0.5)*2)
	}



	private static void resetRegionHighlights(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		def hierarchy = imageData.getHierarchy()
		def annotations = hierarchy.getAnnotationObjects()

		if (annotations.isEmpty()) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No annotation (region) selected.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		// Use the first annotation for simplicity
		def annotation = annotations[0]
		def selectedRegion = annotation.getROI()
		def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }

		def insideRegion = allCells.findAll { cell ->
			selectedRegion.contains(cell.getROI().getCentroidX(), cell.getROI().getCentroidY())
		}

		// Clear highlights
		insideRegion.each { it.setPathClass(null) }

		// ✅ Remove annotation from hierarchy
		hierarchy.removeObject(annotation, false)

		// ✅ Refresh viewer and hierarchy visuals
		Platform.runLater {
			hierarchy.fireHierarchyChangedEvent(null)
			qupath.getViewer().repaint()

			def alert = new Alert(AlertType.INFORMATION,
					"✅ Reset highlights for ${insideRegion.size()} cells and deleted the selected region.")
			alert.initOwner(qupath.getStage())
			alert.show()
		}
	}

	static void openCorticalLayerDialog(QuPathGUI qupath, List<Map<String, String>> rows, File csvFile) {
		Stage stage = new Stage()
		stage.setTitle("Cortical Layer Visualization")
		stage.initOwner(qupath.getStage())
		stage.initModality(Modality.NONE)

		// --- UI Elements ---
		ComboBox<String> layerCombo = new ComboBox<>()
		layerCombo.getItems().addAll(
				"Layer 1", "Layer 2", "Layer 3", "Layer 4", "Layer 5", "Layer 6a", "Layer 6b"
		)
		layerCombo.setValue("Layer 1")

		Slider toleranceSlider = new Slider(1, 50, 20)
		toleranceSlider.setShowTickLabels(true)
		toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10)
		toleranceSlider.setMinorTickCount(4)

		Button runButton = new Button("Run")
		Button resetButton = new Button("Reset")
		Button closeButton = new Button("Close")

		// --- Layout ---
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10)
		grid.setVgap(10)

		grid.add(new Label("Cortical Layer:"), 0, 0)
		grid.add(layerCombo, 1, 0)
		grid.add(new Label("Tolerance (px):"), 0, 1)
		grid.add(toleranceSlider, 1, 1)
		grid.add(runButton, 0, 2)
		grid.add(resetButton, 1, 2)
		grid.add(closeButton, 2, 2)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// --- Button Actions ---
		closeButton.setOnAction { stage.close() }

		resetButton.setOnAction {
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy()
						.getDetectionObjects()
						.findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					def viewer = qupath.getViewer()
					def hierarchy = qupath.getImageData().getHierarchy()
					hierarchy.fireHierarchyChangedEvent(null)
					viewer.repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Cortical layer highlights reset.")
					alert.initOwner(qupath.getStage())
					alert.showAndWait()
				}
			}
		}

		runButton.setOnAction {
			def imageData = qupath.getImageData()
			if (imageData == null) {
				def alert = new Alert(AlertType.WARNING, "⚠️ No image data found.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def hierarchy = imageData.getHierarchy()
			def selectedLayer = layerCombo.getValue()
			def tolerance = toleranceSlider.getValue()

			// Map layer names to cortical layer values
			def layerMapping = [
					"Layer 1": "1",
					"Layer 2": "2", 
					"Layer 3": "3",
					"Layer 4": "4",
					"Layer 5": "5",
					"Layer 6a": "6",
					"Layer 6b": "7"
			]

			def targetLayerValue = layerMapping[selectedLayer]

			// Filter rows by cortical layer
			def matchingRows = rows.findAll { row ->
				row["cortical_layers"] == targetLayerValue
			}

			if (matchingRows.isEmpty()) {
				def alert = new Alert(
						AlertType.WARNING,
					"No cells found for ${selectedLayer} in the CSV data."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			// Build spatial index for fast neighbor lookup
			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x / binSize)}_${(int)(y / binSize)}"
				cellMap[key] << it
			}

			// Find cells within tolerance of matching CSV coordinates
			def matchedCells = [] as Set
			matchingRows.each { row ->
				if (row.x && row.y) {
					def cx = row.x as double
					def cy = row.y as double
					def gx = (int)(cx / binSize)
					def gy = (int)(cy / binSize)
					for (dx in -1..1) {
						for (dy in -1..1) {
							def key = "${gx + dx}_${gy + dy}"
							def group = cellMap[key]
							group.each {
								def dx2 = it.getROI().getCentroidX() - cx
								def dy2 = it.getROI().getCentroidY() - cy
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance)) {
									matchedCells << it
								}
							}
						}
					}
				}
			}

			// Assign PathClass and highlight cells
			def pathClass = PathClass.fromString("Cortical-${selectedLayer.replace(" ", "")}")
			matchedCells.each { it.setPathClass(pathClass) }
			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				def viewer = qupath.getViewer()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()

				def alert = new Alert(
						AlertType.INFORMATION,
					"✅ ${selectedLayer} highlighted: ${matchedCells.size()} cells found."
				)
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
			}

			// Export matched cell centroids to CSV
			def exportFile = new File(
					csvFile.getParent(),
					"cortical_layer_${selectedLayer.replace(" ", "_")}_cells.csv"
			)
			exportFile.withWriter { w ->
				w.write("CellX,CellY,CorticalLayer\n")
				matchedCells.each {
					def roi = it.getROI()
					w.write("${roi.getCentroidX()},${roi.getCentroidY()},${selectedLayer}\n")
				}
			}
			println "Exported to: ${exportFile.absolutePath}"
		}
	}

	private static void runCosineSimilaritySearch(QuPathGUI qupath) {
		def imageData = qupath.getImageData()
		if (imageData == null) {
			def alert = new Alert(AlertType.WARNING, "⚠️ No image data available.")
			alert.initOwner(qupath.getStage())
			alert.show()
			return
		}

		Stage stage = new Stage()
		stage.setTitle("Cell Similarity Search")
		stage.initOwner(qupath.getStage())
		stage.initModality(Modality.NONE)

		// Create UI elements
		TextField filePathField = new TextField()
		filePathField.setEditable(false)
		filePathField.setPrefWidth(300)

		TextField topNField = new TextField("10")
		topNField.setPrefWidth(100)
		topNField.setPromptText("Enter number of cells")

		Button browseButton = new Button("Browse JSON")
		Button runButton = new Button("Run")
		Button resetButton = new Button("Reset")
		Button closeButton = new Button("Close")

		// Layout
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10)
		grid.setVgap(10)

		grid.add(new Label("Similarity JSON File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		grid.add(new Label("Top N Cells:"), 0, 1)
		grid.add(topNField, 1, 1)

		HBox buttonBox = new HBox(10)
		buttonBox.getChildren().addAll(runButton, resetButton, closeButton)
		grid.add(buttonBox, 1, 2)

		// File chooser setup
		FileChooser chooser = new FileChooser()
		chooser.setTitle("Select JSON File")
		chooser.getExtensionFilters().add(
			new FileChooser.ExtensionFilter("JSON files", "*.json")
		)

		// Button actions
		browseButton.setOnAction {
			File file = chooser.showOpenDialog(stage)
			if (file != null) {
				filePathField.setText(file.getAbsolutePath())
			}
		}

		closeButton.setOnAction { stage.close() }

		resetButton.setOnAction {
			def hierarchy = imageData.getHierarchy()
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			allCells.each { it.setPathClass(null) }
			Platform.runLater {
				hierarchy.fireHierarchyChangedEvent(null)
				qupath.getViewer().repaint()
			}
		}

		runButton.setOnAction {
			File jsonFile = new File(filePathField.getText())
			if (!jsonFile.exists()) {
				def alert = new Alert(AlertType.WARNING, "Please select a valid JSON file.")
				alert.initOwner(stage)
				alert.show()
				return
			}

			int topN
			try {
				topN = Integer.parseInt(topNField.getText().trim())
				if (topN <= 0) throw new NumberFormatException()
			} catch (NumberFormatException e) {
				def alert = new Alert(AlertType.WARNING, "Please enter a valid positive number for Top N cells.")
				alert.initOwner(stage)
				alert.show()
				return
			}

			// Parse JSON and process similarities
			def gson = new Gson()
			def similarities
			try {
				def mapType = new TypeToken<Map<String, List<Double>>>() {}.getType()
				similarities = gson.fromJson(new FileReader(jsonFile), mapType)
			} catch (Exception e) {
				def alert = new Alert(AlertType.ERROR, "Error parsing JSON file: ${e.message}")
				alert.initOwner(stage)
				alert.show()
				return
			}

			// Get all cells from QuPath
			def hierarchy = imageData.getHierarchy()
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }

			// Create a map of cell coordinates to cell objects
			def cellMap = [:]
			allCells.each { cell ->
				def roi = cell.getROI()
				def key = "${roi.getCentroidX()}_${roi.getCentroidY()}"
				cellMap[key] = cell
			}

			// Process each entry in the JSON
			similarities.each { filename, values ->
				// Extract x, y coordinates from filename (format: x_ys1_id.tif)
				def coords = filename.split('_')
				if (coords.size() >= 2) {
					def x = coords[0]
					def y = coords[1].replace('ys1', '')
					
					// Find the closest cell to these coordinates
					def targetKey = "${x}_${y}"
					def targetCell = cellMap[targetKey]
					
					if (targetCell) {
						// Create list of cells with their similarity scores
						def cellScores = []
						allCells.eachWithIndex { cell, idx ->
							if (idx < values.size()) {
								cellScores << [cell: cell, score: values[idx]]
							}
						}
						
						// Sort by similarity score (highest first) and take top N
						def topCells = cellScores.sort { -it.score }.take(topN)
						
						// Highlight cells
						def className = PathClass.fromString("Similar-${x}_${y}")
						topCells.each { entry ->
							entry.cell.setPathClass(className)
						}
						
						// Print scores for verification
						println "Top ${topN} similar cells for ${x}_${y}:"
						topCells.eachWithIndex { entry, idx ->
							def cell = entry.cell
							def score = entry.score
							println "${idx + 1}. Cell at (${cell.ROI.centroidX}, ${cell.ROI.centroidY}) - Similarity: ${score}"
						}
					}
				}
			}

			// Update display
			Platform.runLater {
				hierarchy.fireHierarchyChangedEvent(null)
				qupath.getViewer().repaint()
				
				def alert = new Alert(AlertType.INFORMATION, 
					"✅ Cell similarity search complete.\nHighlighted top ${topN} similar cells for each target.")
				alert.initOwner(stage)
				alert.show()
			}
		}

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()
	}
}
