package qupath.ext.template
import javafx.scene.paint.Stop
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.CycleMethod
import javafx.beans.value.ChangeListener
import javafx.concurrent.Task
import javafx.geometry.Pos
import javafx.scene.control.Alert.AlertType
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
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


		def csvClusterItem = new MenuItem("Community Search")
		csvClusterItem.setOnAction(e -> runCSVClusterSearch(qupath))
		comprehensiveMenu.getItems().add(csvClusterItem)

		def phenotypeFinderItem = new MenuItem("Phenotype Finder")
		phenotypeFinderItem.setOnAction(e -> runPhenotypeFinder(qupath))
		comprehensiveMenu.getItems().add(phenotypeFinderItem)

		def resetRegionItem = new MenuItem("Reset Region Highlights")
		resetRegionItem.setOnAction(e -> resetRegionHighlights(qupath))

		// 5) Add Channel Viewer item
		def channelViewerItem = new MenuItem("Channel Viewer")
		channelViewerItem.setOnAction { e ->
			runChannelViewer(qupath)
		}

		def exprMatrixItem = new MenuItem("Expression Matrix")
		exprMatrixItem.setOnAction { e ->
			runExpressionMatrix(qupath)
		}



		mainMenu.getItems().addAll(quickSearchMenu, comprehensiveMenu, resetRegionItem,channelViewerItem, exprMatrixItem)
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
					}
				} catch (Exception ex) {
					ex.printStackTrace()
					Platform.runLater {
						progressLabel.textProperty().unbind()
						progressLabel.setText("❌ Failed: ${ex.message}")
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
		Label progressLabel = new Label("Idle")

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


		VBox layout = new VBox(10,
				new VBox(5, new Label("Marker Selections:"), cbMarkerSelectAll, partitionCheckboxes(markerCheckboxes, 4)),
				new VBox(5, new Label("Morphological Features:"), cbMorphSelectAll, partitionCheckboxes(morphCbs, 3)),
				new VBox(5, new Label("Neighborhood Markers:"), cbSurroundSelectAll, partitionCheckboxes(surroundCheckboxes, 4)),
				new HBox(10, new Label("Top N:"), tfTopN, new Label("Radius (µm):"), tfRadius, new Label("Weight k:"), tfWeight),
                new VBox(5, new Label("Other Features:"), cbLocalDensity),
                new VBox(5, new Label("Operation:"), cbUnion, cbIntersection, cbSubtract, cbContrastive, cbCompetitive),
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
		def hierarchy = qupath.getImageData().getHierarchy()
		def selected = hierarchy.getSelectionModel()
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
		def names = selected[0].getMeasurementList().getMeasurementNames()
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
				double cov = (0..<k).sum { t ->
					(data[i][t]-meanI)*(data[j][t]-meanJ)
				}/(k-1)
				double stdJ = Math.sqrt(data[j].collect{ (it-meanJ)**2 }.sum()/(k-1))
				corr[i][j] = cov / (stdI*stdJ + 1e-12)
			}
		}

		// 5) Shorten labels (“Cell: NeuN mean” → “NeuN”)
		def shortLabels = featureNames.collect { fn ->
			fn.replaceFirst(/^Cell:\s*/, "").replaceFirst(/\s*mean$/, "")
		}

		// 6) Build the heatmap grid
		GridPane gp = new GridPane()
		gp.hgap = 1; gp.vgap = 1; gp.padding = new Insets(5)

		// 6a) Headers
		shortLabels.eachWithIndex { lab, c ->
			Label lbl = new Label(lab)
			lbl.rotate = -45
			lbl.minWidth = 30; lbl.prefWidth = 30
			gp.add(lbl, c+1, 0)
		}
		// 6b) Cells
		shortLabels.eachWithIndex { lab, r ->
			gp.add(new Label(lab), 0, r+1)
			(0..<m).each { c ->
				double v = corr[r][c]
				Rectangle rect = new Rectangle(20,20)
				rect.fill = getColorForValue(v*2)  // [–1…1]→[–2…2]
				Tooltip.install(rect, new Tooltip(String.format("%.2f", v)))
				gp.add(rect, c+1, r+1)
			}
		}

		// 7) Scrollable pane
		ScrollPane heatmapScroll = new ScrollPane(gp)
		heatmapScroll.fitToWidth  = true
		heatmapScroll.fitToHeight = true
		HBox.setHgrow(heatmapScroll, Priority.ALWAYS)

		// 8) Build the **horizontal** gradient legend (blue→white→red)
		Label legendTitle = new Label("Mean Expression")
		legendTitle.style = "-fx-font-weight: bold;"

		def stops = [
				new Stop(0.0, Color.web("#2166ac")),  // –1 → blue
				new Stop(0.5, Color.web("#f7f7f7")),  //  0 → white
				new Stop(1.0, Color.web("#b2182b"))   // +1 → red
		]
		LinearGradient lg = new LinearGradient(
				0,0, 1,0, true, CycleMethod.NO_CYCLE, stops
		)
		Rectangle gradientBar = new Rectangle(200, 20)
		gradientBar.fill = lg

		// Tick labels
		Label minLabel = new Label("0")
		Label midLabel = new Label("0.5")
		Label maxLabel = new Label("1")

		HBox tickBox = new HBox()
		tickBox.alignment = Pos.CENTER
		tickBox.prefWidthProperty().bind(gradientBar.widthProperty())
		tickBox.children.addAll(minLabel, midLabel, maxLabel)
		[minLabel, midLabel, maxLabel].each { lbl ->
			HBox.setHgrow(lbl, Priority.ALWAYS)
			lbl.maxWidth = Double.MAX_VALUE
		}
		minLabel.alignment = Pos.CENTER_LEFT
		midLabel.alignment = Pos.CENTER
		maxLabel.alignment = Pos.CENTER_RIGHT

		// Assemble legend
		VBox legendBox = new VBox(5, legendTitle, gradientBar, tickBox)
		legendBox.alignment = Pos.TOP_CENTER
		legendBox.padding = Insets.EMPTY

		// 9) Final layout: heatmap + legend side‑by‑side
		HBox root = new HBox(10, heatmapScroll, legendBox)
		root.alignment = Pos.CENTER_LEFT
		root.padding = Insets.EMPTY

		// 10) Show it
		Stage stage = new Stage()
		stage.title = "Feature Correlation (n=${k} cells, m=${m} features)"
		stage.scene = new Scene(root)
		stage.sizeToScene()
		stage.resizable = true
		stage.show()
	}

	// --- CSV-BASED CLUSTER SEARCH ---
	static void runCSVClusterSearch(QuPathGUI qupath) {
		Stage stage = new Stage()
		stage.setTitle("CSV Cluster Search")
		stage.initOwner(qupath.getStage()) // No modality!


		// UI Elements
		TextField filePathField = new TextField(); filePathField.setEditable(false)
		ComboBox<String> comboBox = new ComboBox<>()
		comboBox.getItems().addAll("level_1", "level_2", "level_3", "level_4", "level_5", "level_6")
		comboBox.setValue("level_1")

		Slider toleranceSlider = new Slider(1, 50, 20)
		toleranceSlider.setShowTickLabels(true); toleranceSlider.setShowTickMarks(true)
		toleranceSlider.setMajorTickUnit(10); toleranceSlider.setMinorTickCount(4)

		Button browseButton = new Button("Browse CSV")
		Button runButton = new Button("Run")
		runButton.setDisable(true)
		Button resetButton = new Button("Reset Highlights")

		// Layout
		GridPane grid = new GridPane()
		grid.setPadding(new Insets(20))
		grid.setHgap(10); grid.setVgap(10)

		grid.add(new Label("CSV File:"), 0, 0)
		grid.add(filePathField, 1, 0)
		grid.add(browseButton, 2, 0)

		grid.add(new Label("Cluster Level:"), 0, 1)
		grid.add(comboBox, 1, 1)

		grid.add(new Label("Tolerance (px):"), 0, 2)
		grid.add(toleranceSlider, 1, 2, 2, 1)

		grid.add(runButton, 1, 3)
		grid.add(resetButton, 2, 3)

		Scene scene = new Scene(grid)
		stage.setScene(scene)
		stage.show()

		// Internal data cache
		def rows = []
		def header = []
		File csvFile = null

		// Browse Action
		browseButton.setOnAction({
			FileChooser fileChooser = new FileChooser()
			fileChooser.setTitle("Select CSV File")
			fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"))
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
						for (int i = 0; i < header.size(); i++)
							row[header[i]] = (i < parts.length ? parts[i] : "")
						rows << row
					}
				}
				runButton.setDisable(false)
			}
		})

		resetButton.setOnAction({
			def imageData = qupath.getImageData()
			if (imageData != null) {
				def allCells = imageData.getHierarchy().getDetectionObjects().findAll { it.isCell() }
				allCells.each { it.setPathClass(null) }
				Platform.runLater {
					def viewer = qupath.getViewer()
					def hierarchy = qupath.getImageData().getHierarchy()
					hierarchy.fireHierarchyChangedEvent(null)
					viewer.repaint()

					def alert = new Alert(AlertType.INFORMATION, "✅ Highlights reset.")
					alert.initOwner(qupath.getStage())  // Tie alert to main window
					alert.showAndWait()
				}
			}
		})

		// Run Button Action
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
			def selected = hierarchy.getSelectionModel().getSelectedObjects().findAll { it.isCell() }
			if (selected.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "⚠️ Please select a cell to run cluster search.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

			def chosenLevel = comboBox.getValue()
			def tolerance = toleranceSlider.getValue()

			// Collect cluster labels from selected cells
			def selectedLabels = [] as Set
			selected.each { cell ->
				def cx = cell.getROI().getCentroidX()
				def cy = cell.getROI().getCentroidY()

				def nearest = rows.min { row ->
					if (!row.x || !row.y) return Double.MAX_VALUE
					def dx = (row.x as double) - cx
					def dy = (row.y as double) - cy
					return dx * dx + dy * dy
				}
				if (nearest != null && nearest[chosenLevel] != null)
					selectedLabels << nearest[chosenLevel]
			}

			if (selectedLabels.isEmpty()) {
				def alert = new Alert(AlertType.WARNING, "No matching clusters found in CSV for selected cells.")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
				return
			}

// Find all matching rows with *any* of the selected cluster labels
			def matchingRows = rows.findAll { row -> selectedLabels.contains(row[chosenLevel]) }

			// Spatial bin map
			def binSize = tolerance
			def allCells = hierarchy.getDetectionObjects().findAll { it.isCell() }
			def cellMap = [:].withDefault { [] }
			allCells.each {
				def x = it.getROI().getCentroidX()
				def y = it.getROI().getCentroidY()
				def key = "${(int)(x/binSize)}_${(int)(y/binSize)}"
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
								if ((dx2 * dx2 + dy2 * dy2) <= (tolerance * tolerance))
									matchedCells << it
							}
						}
					}
				}
			}
			def labelStr = selectedLabels.join("_").replaceAll("[^a-zA-Z0-9_]", "_")
			def pathClass = PathClass.fromString("Cluster-${chosenLevel}-${labelStr}")

			matchedCells.each { it.setPathClass(pathClass) }

			hierarchy.getSelectionModel().clearSelection()
			hierarchy.getSelectionModel().setSelectedObjects(matchedCells.toList(), null)

			Platform.runLater {
				def viewer = qupath.getViewer()
				hierarchy.fireHierarchyChangedEvent(null)
				viewer.repaint()

				def labelSummary = selectedLabels.join(", ")
				def alert = new Alert(AlertType.INFORMATION,
						"✅ Cluster highlight complete for ${chosenLevel} in [${labelSummary}]\nFound ${matchedCells.size()} cells")
				alert.initOwner(qupath.getStage())
				alert.showAndWait()
			}


			// Export matched
			def exportFile = new File(csvFile.getParent(), "matched_cells_${chosenLevel}_${selectedLabels.join('_')}.csv")
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
		File selectedCSV = null;
		List<Map<String, String>> cachedRows = null;
		boolean hasNeuN = false;
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

		def markerLabels = measurementNames.findAll { it.startsWith("Cell: NeuN") && it.endsWith(" mean") }

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

		// Upload CSV UI
		Stage uploadStage = new Stage();
		uploadStage.setTitle("Upload Phenotype CSV");
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
				selectedCSV = file;
				pathField.setText(file.getAbsolutePath());
			}
		}

		cancelUploadButton.setOnAction {
			uploadStage.close();
		}

		runUploadButton.setOnAction {
			if (selectedCSV == null || !selectedCSV.exists()) {
				def alert = new Alert(Alert.AlertType.WARNING, "Please select a valid CSV file.");
				alert.initOwner(qupath.getStage());
				alert.show();
				return;
			}

			// Read the CSV
			cachedRows = []
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


/**
 * Map a –2…+2 value onto a continuous cool–warm gradient:
 *   deep blue → white → deep red
 */
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
			// phenotype label
			Label name = new Label(pheno)
			name.minWidth = 120
			grid.add(name, 0, r + 1)

			// heatmap rectangles
			markerCols.eachWithIndex { m, c ->
				double raw  = heatmapMatrix[pheno][m] ?: 0.0
				double norm = normalize(raw)
				Rectangle rect = new Rectangle(30, 20)
				rect.fill = getColorForValue(norm)
				Tooltip.install(rect, new Tooltip(
						"$pheno – $m: ${String.format('%.2f', raw)}"
				))
				grid.add(rect, c + 1, r + 1)
			}

			// count bar
			int cnt = cellCounts.getOrDefault(pheno, 0)
			double barW = Math.min(100.0, cnt / 200.0)
			Rectangle countBar = new Rectangle(barW, 20)
			countBar.fill = FxColor.GRAY
			Tooltip.install(countBar, new Tooltip("$pheno: $cnt cells"))
			grid.add(countBar, markerCols.size() + 1, r + 1)

			// count label
			grid.add(new Label("$cnt"), markerCols.size() + 2, r + 1)
		}

		// 5) Wrap heatmap in a ScrollPane
		ScrollPane scroll = new ScrollPane(grid)
		scroll.fitToWidth  = true
		scroll.fitToHeight = true

		// 6) Build the continuous gradient legend
		// 6a) Title
		Label legendTitle = new Label("Mean expression")
		legendTitle.style = "-fx-font-weight: bold;"

		// 6b) Gradient bar (blue→white→red)
		def stops = [
				new Stop(0.0, getColorForValue(-2)),
				new Stop(0.5, getColorForValue(0)),
				new Stop(1.0, getColorForValue(2))
		]
		LinearGradient lg = new LinearGradient(
				0, 0, 1, 0, true, CycleMethod.NO_CYCLE, stops
		)
		Rectangle gradientBar = new Rectangle(200, 20)
		gradientBar.fill = lg

		// 6c) Tick labels
		Label minLabel = new Label("0")
		Label midLabel = new Label("0.5")
		Label maxLabel = new Label("1")

		HBox tickBox = new HBox()
		tickBox.alignment = Pos.CENTER
		// bind tickBox width to gradientBar width
		tickBox.prefWidthProperty().bind(gradientBar.widthProperty())
		tickBox.children.addAll(minLabel, midLabel, maxLabel)

		// let labels spread out
		[minLabel, midLabel, maxLabel].each { lbl ->
			HBox.setHgrow(lbl, Priority.ALWAYS)
			lbl.maxWidth = Double.MAX_VALUE
		}
		minLabel.alignment = Pos.CENTER_LEFT
		midLabel.alignment = Pos.CENTER
		maxLabel.alignment = Pos.CENTER_RIGHT

		// 6d) Assemble legend VBox
		VBox legend = new VBox(5, legendTitle, gradientBar, tickBox)
		legend.alignment = Pos.TOP_CENTER

		// 7) Layout heatmap + legend side by side
		HBox root = new HBox(10, scroll, legend)
		root.padding = new Insets(10)
		root.alignment = Pos.TOP_LEFT

		// 8) Show the scene
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

			// 2) Build grid of 175×175 patches
			def server   = imageData.getServer()
			def channels = viewer.getImageDisplay().availableChannels()
			int nChan = channels.size(), nCell = cells.size()
			int w = 175, h = 175, halfW = w.intdiv(2), halfH = h.intdiv(2)

			def grid = new GridPane()
			grid.hgap = 5; grid.vgap = 5; grid.padding = new Insets(10)
			grid.add(new Text('Channel \\ Cell'), 0, 0)
			cells.eachWithIndex { cell, j -> grid.add(new Text("Cell ${j+1}"), j+1, 0) }
			channels.eachWithIndex { info, i -> grid.add(new Text(info.name), 0, i+1) }

			cells.eachWithIndex { cell, j ->
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


}
