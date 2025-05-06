
# Cell Search Engine

A powerful QuPath extension that enables **advanced cell search, clustering, and phenotype highlighting** in high-resolution tissue images.  
Supports **Quick Search**, **Neighborhood Analysis**, **Multi-Query Search**, **Phenotype Highlighting**, and **CSV Cluster-Based Search**.

---

## 🛠️ Tools and Technologies

- **Java 17** (tested with Java 17.0.2)
- **Groovy**
- **QuPath** (tested with version `0.4.0`)
- **IntelliJ IDEA** (for development)
- **JavaFX** (for UI)
- **Apache Commons Math3** (for distance calculations)

---

## 🚀 Installation

1. **Clone the repository:**

    ```bash
    git clone https://github.com/saikiran888/Cell_Search_Engine.git
    cd Cell_Search_Engine
    ```

2. **Build the extension using Gradle:**

    ```bash
    ./gradlew clean build
    ```

    After successful build, the `.jar` file will be generated at:

    ```
    build/libs/cell-search-engine-extension-1.0.0.jar
    ```

3. **Install into QuPath:**

    - Open QuPath.
    - **Drag and drop** the generated `.jar` file into QuPath.
    - Restart QuPath.
    - You should now see the extension listed under:
      ```
      Extensions > Cell Search Engine
      ```

---

## 📖 How to Use

After installation, under `Extensions > Cell Search Engine`, you will see:

- **Quick Cell Search**
  - **Unified Search**:
    - Select a single cell ➔ Perform **Neighborhood Search**.
    - Select multiple cells ➔ Perform **Multi-Query Search** (Union, Intersection, Subtract, Contrastive, Competitive Boost).
    - After running Unified Search, select ≥2 cells ➔ Compute pairwise Pearson correlations of cell features.
    -  Displays a correlation matrix heatmap with legend.

- **Comprehensive Search**
  - **Community Search (CSV Cluster Search)**:
    - Upload a CSV containing cluster labels.
    - Highlight cells matching specific clusters.
  - **Phenotype Finder**:
    - Upload a CSV containing phenotype labels.
    - Highlight specific phenotypes like immune cells, tumor cells, etc.
    - Phenotype vs Marker Mean Expression Heatmap
    -  Generates a heatmap normalized to –2…+2 with a continuous gradient legend.
    -   Includes cell count bars per phenotype.
- **Reset Region Highlights**:
    - Reset and clear highlights in selected regions.

- **Channel Viewer**
    - Select one or more cells ➔ Display a grid of 175×175 px patches for each channel and cell.
    - Scrollable, resizable window to inspect channel intensities visually.



---

## 🧠 Core Functionalities

| Feature | Description |
|:---|:---|
| **Neighborhood Search** | Find cells spatially similar to a target cell based on marker expressions and morphology within a specified radius. |
| **Multi-Query Search** | Perform advanced cell search operations such as Union, Intersection, Subtraction, Contrastive Analysis, and Competitive Boost between multiple selected cells. |
| **Phenotype Finder** | Highlight cells based on precomputed phenotypic classifications from a CSV file (e.g., T cells, B cells, Macrophages). |
| **CSV Cluster Search** | Match and highlight cells based on external clustering results (e.g., Infomap clusters) provided in a CSV. |
| **Local Density Feature** | Compute and use local cell density as an additional feature for neighborhood search. |
| **Export Matched Cells** | Save coordinates of matched cells into a CSV file directly. |
| **Reset Highlights** | Quickly reset highlights for cells within an annotated region. |
| **Channel Viewer** | Display a grid of channel-specific image patches (175×175 px) for selected cells, enabling visual inspection of channel intensities. |


---

## 🧩 Unified Search Modes

Depending on what you select:

- **Single Cell Selected** ➔ Runs **Neighborhood Search**.
- **Multiple Cells Selected** ➔ Runs **Multi-Query Search** with your chosen operation mode:
  - Union
  - Intersection
  - Subtract
  - Contrastive
  - Competitive Boost


---

## ⚠️ Potential Errors and Troubleshooting

| Error | Reason | Solution |
|:---|:---|:---|
| **Cannot find Java 17** | Wrong Java SDK is configured. | Make sure **Project SDK** is set to **Java 17** (Settings → Project Structure → SDKs). |
| **`./gradlew: Permission denied`** (on Mac/Linux) | Gradlew script is not executable. | Run `chmod +x gradlew` before building. |
| **Extension not appearing in QuPath** | `.jar` file not properly built or installed. | Ensure you drag-and-drop the `.jar` into QuPath's `extensions/` folder and restart QuPath. |
| **Version mismatch error** | Built for wrong QuPath version. | Confirm you are using **QuPath v0.4.0** or compatible. Rebuild if necessary. |
| **Gradle Build Failed** | Wrong Gradle JVM or project misconfiguration. | In IntelliJ, ensure **Gradle JVM** is set to **Project SDK 17** (Settings → Build Tools → Gradle). |
| **No image open in QuPath** | Trying to run the extension without an image loaded. | Open a slide/image before using the extension. |
| **"Please select at least one cell" warning** | No cell selected for running search operations. | Select one or more cells in the image before running. |
| **Phenotype or CSV search not working** | Missing or wrongly formatted CSV file. | Ensure your CSV has correct headers and coordinate fields (`x`, `y`, etc.). |
| **Reset region fails** | No annotation selected. | Draw and select an annotation before trying to reset region highlights. |

---

## 📂 Folder Structure

```bash
Cell_Search_Engine/
├── src/
│   └── main/
│       └── groovy/
│           └── qupath/ext/template/DemoGroovyExtension.groovy
├── README.md
├── build.gradle
└── build/
    └── libs/
        └── cell-search-engine-extension-1.0.0.jar
```
## 📸 Example Screenshots

| Unified Search | Phenotype Finder | Cluster Search | Search Result |
|:--------------:|:----------------:|:--------------:|:-------------:|
| ![image](https://github.com/user-attachments/assets/20fe4312-174c-48cc-b066-0ae602c12b25) | ![image](https://github.com/user-attachments/assets/9ab760b1-da3e-466d-9702-1bafa4107af6) | ![image](https://github.com/user-attachments/assets/a9a04d47-8930-48a5-a875-d47094c9663b) | ![image](https://github.com/user-attachments/assets/53f39ea4-8da6-4799-b9cb-e102b67b7ddc) |

## ©️ Copyright and License

Copyright (c) 2025 Saikiran Mandula.

## 🙋‍♂️ Author

**Saikiran Mandula**  
[GitHub](https://github.com/saikiran888) | [Portfolio](https://saikiranmandula.vercel.app/) | [LinkedIn](https://www.linkedin.com/in/saikiranmandula/)

---


